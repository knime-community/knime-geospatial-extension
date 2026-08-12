/*
 * ------------------------------------------------------------------------
 *  Copyright by KNIME AG, Zurich, Switzerland
 *  Website: http://www.knime.com; Email: contact@knime.com
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License, Version 3, as
 *  published by the Free Software Foundation.
 * ------------------------------------------------------------------------
 */
package org.knime.geospatial.io.geopackagewriter;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.geotools.api.data.SimpleFeatureWriter;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.api.filter.Filter;
import org.geotools.api.data.Transaction;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.geopkg.FeatureEntry;
import org.geotools.geopkg.GeoPackage;
import org.knime.core.data.DataRow;
import org.knime.core.data.DataTableSpec;
import org.knime.core.node.BufferedDataTable;
import org.knime.core.node.CanceledExecutionException;
import org.knime.core.node.ExecutionContext;
import org.knime.core.node.InvalidSettingsException;
import org.knime.core.node.KNIMEException;
import org.knime.core.node.message.Message;
import org.knime.filehandling.core.connections.FSFiles.LocalFileHandle;
import org.knime.filehandling.core.connections.FSPath;
import org.knime.filehandling.core.defaultnodesettings.status.StatusMessage;
import org.knime.geospatial.core.data.GeoValue;
import org.knime.geospatial.io.geopackagewriter.GeoPackageWriterNodeParameters.ExistingFile;
import org.knime.geospatial.io.util.GeoFileNames;
import org.knime.geospatial.io.util.GeoTypeMapping;
import org.knime.geospatial.io.util.LocalFileStaging;
import org.knime.node.DefaultModel.ConfigureInput;
import org.knime.node.DefaultModel.ConfigureOutput;
import org.knime.node.DefaultModel.ExecuteInput;
import org.knime.node.DefaultModel.ExecuteOutput;
import org.knime.node.DefaultNode;
import org.knime.node.DefaultNodeFactory;
import org.knime.node.parameters.widget.file.FileSelectionConfig;
import org.locationtech.jts.geom.Geometry;

/**
 * Node factory for GeoPackage Writer, 1:1 mirroring {@code GeoPackageWriterNode} in the Python Geospatial Analytics
 * Extension's {@code knime_extension/src/nodes/io.py}, including the exact "if exists" nuance the Python node
 * documents: the overwrite policy only guards whole-<em>file</em> overwrite — writing into an already-existing
 * file always proceeds and silently replaces the named layer.
 */
public final class GeoPackageWriterNodeFactory extends DefaultNodeFactory {

    private static final FileSelectionConfig FILE_SELECTION_CONFIG = FileSelectionConfig.builder().build();

    private static final DefaultNode NODE = DefaultNode.create() //
        .name("GeoPackage Writer") //
        .icon("./GeoPackageWriter.png") //
        .shortDescription("""
                Write GeoPackage layer.
                """) //
        .fullDescription("""
                This node writes the data as a new GeoPackage file, or as a layer into an existing file. If the
                file and layer already exist, the layer is overwritten without a warning.
                """) //
        .sinceVersion(5, 13, 0) //
        .ports(p -> p //
            .addInputTable("Geodata table", "Geodata from the input file path.")) //
        .model(m -> m //
            .parametersClass(GeoPackageWriterNodeParameters.class) //
            .configure(GeoPackageWriterNodeFactory::configure) //
            .execute(GeoPackageWriterNodeFactory::execute)) //
        .nodeType(NodeType.Sink);

    /** Constructor used by the framework. */
    public GeoPackageWriterNodeFactory() {
        super(NODE);
    }

    private static void configure(final ConfigureInput in, final ConfigureOutput out) throws InvalidSettingsException {
        final var parameters = in.<GeoPackageWriterNodeParameters> getParameters();
        final DataTableSpec inSpec = in.getInTableSpec(0);
        if (parameters.m_geoColumn == null || !inSpec.containsName(parameters.m_geoColumn)
            || !inSpec.getColumnSpec(parameters.m_geoColumn).getType().isCompatible(GeoValue.class)) {
            throw new InvalidSettingsException("No valid geometry column selected");
        }
        parameters.m_outputFile.validateOnConfigure(FILE_SELECTION_CONFIG, Optional.empty(), out::setWarningMessage);
    }

    private static void execute(final ExecuteInput in, final ExecuteOutput out)
        throws CanceledExecutionException, KNIMEException {
        final var parameters = in.<GeoPackageWriterNodeParameters> getParameters();
        final BufferedDataTable table = in.getInTable(0);
        final DataTableSpec spec = table.getDataTableSpec();
        final int geoColIdx = spec.findColumnIndex(parameters.m_geoColumn);
        final ExecutionContext exec = in.getExecutionContext();

        final Consumer<StatusMessage> statusConsumer = msg -> {
            if (msg.getType() == StatusMessage.MessageType.WARNING || msg.getType() == StatusMessage.MessageType.ERROR) {
                out.setWarningMessage(msg.getMessage());
            }
        };

        try (final var accessor = parameters.m_outputFile.getPathAccessor(FILE_SELECTION_CONFIG, Optional.empty())) {
            final FSPath rawDestPath = accessor.getOutputPath(statusConsumer);
            final FSPath destPath = (FSPath)rawDestPath.getParent()
                .resolve(GeoFileNames.ensureExtension(rawDestPath.getFileName().toString(), ".gpkg"));

            final boolean destExists = Files.exists(destPath);
            if (destExists && parameters.m_overwritePolicy == ExistingFile.FAIL) {
                throw new KNIMEException(
                    "Output file \"" + destPath + "\" already exists - must not overwrite as per user setting");
            }

            Path localFile;
            LocalFileHandle existingLocal = null;
            if (destExists) {
                existingLocal = LocalFileStaging.resolveExistingToLocalFile(destPath);
                localFile = Path.of(existingLocal.path());
            } else {
                localFile = LocalFileStaging.createLocalStagingFile("geopackagewriter-", ".gpkg");
            }

            try {
                writeLayer(localFile, table, geoColIdx, parameters.m_layer, exec);
                final var openOptions =
                    new java.nio.file.OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING};
                LocalFileStaging.uploadFileTo(localFile, destPath, openOptions);
            } finally {
                if (existingLocal != null) {
                    existingLocal.close();
                } else {
                    LocalFileStaging.deleteQuietly(localFile);
                }
            }
        } catch (final CanceledExecutionException | KNIMEException e) {
            throw e;
        } catch (final Exception e) {
            throw KNIMEException.of(Message.fromSummary("Error writing file: " + e.getMessage()), e);
        }
    }

    private static void writeLayer(final Path localFile, final BufferedDataTable table, final int geoColIdx,
        final String layerName, final ExecutionContext exec) throws Exception {
        try (var geoPackage = new GeoPackage(new File(localFile.toString()))) {
            final DataTableSpec spec = table.getDataTableSpec();
            final var crs = table.size() == 0 ? org.geotools.referencing.crs.DefaultGeographicCRS.WGS84
                : GeoTypeMapping.toCoordinateReferenceSystem((GeoValue)table.iterator().next().getCell(geoColIdx));

            final var builder = new SimpleFeatureTypeBuilder();
            builder.setName(layerName);
            for (int i = 0; i < spec.getNumColumns(); i++) {
                final String name = spec.getColumnSpec(i).getName();
                if (i == geoColIdx) {
                    builder.add(name, Geometry.class, crs);
                } else {
                    builder.add(name, String.class);
                }
            }
            final SimpleFeatureType featureType = builder.buildFeatureType();

            final List<FeatureEntry> existingEntries = geoPackage.features();
            FeatureEntry entry = null;
            for (final FeatureEntry candidate : existingEntries) {
                if (candidate.getTableName().equals(layerName)) {
                    entry = candidate;
                    break;
                }
            }
            final boolean layerAlreadyExists = entry != null;
            if (!layerAlreadyExists) {
                entry = new FeatureEntry();
                entry.setTableName(layerName);
                geoPackage.create(entry, featureType);
            }

            // append=false: replace the layer's existing content rather than appending to it, matching the Python
            // node's documented "layer will be overwritten without a warning" behavior.
            try (SimpleFeatureWriter writer =
                geoPackage.writer(entry, false, Filter.INCLUDE, Transaction.AUTO_COMMIT)) {
                long rowIdx = 0;
                final long rowCount = table.size();
                for (final DataRow row : table) {
                    exec.checkCanceled();
                    if (rowCount > 0) {
                        exec.setProgress((double)rowIdx / rowCount, "Writing row " + rowIdx + "/" + rowCount);
                    }
                    final var feature = writer.next();
                    for (int i = 0; i < row.getNumCells(); i++) {
                        final var cell = row.getCell(i);
                        if (i == geoColIdx) {
                            feature.setAttribute(i, cell.isMissing() ? null : GeoTypeMapping.toJtsGeometry((GeoValue)cell));
                        } else {
                            feature.setAttribute(i, cell.isMissing() ? null : cell.toString());
                        }
                    }
                    writer.write();
                    rowIdx++;
                }
            }
        }
    }
}

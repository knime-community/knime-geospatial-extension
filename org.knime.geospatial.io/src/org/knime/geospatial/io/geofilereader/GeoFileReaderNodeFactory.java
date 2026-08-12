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
package org.knime.geospatial.io.geofilereader;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.geotools.api.data.DataStore;
import org.geotools.api.data.FileDataStore;
import org.geotools.api.data.SimpleFeatureSource;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.data.shapefile.ShapefileDataStoreFactory;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.feature.FeatureCollection;
import org.geotools.geojson.feature.FeatureJSON;
import org.knime.core.data.DataCell;
import org.knime.core.data.DataRow;
import org.knime.core.data.DataTableSpec;
import org.knime.core.data.def.DefaultRow;
import org.knime.core.node.BufferedDataContainer;
import org.knime.core.node.BufferedDataTable;
import org.knime.core.node.CanceledExecutionException;
import org.knime.core.node.InvalidSettingsException;
import org.knime.core.node.KNIMEException;
import org.knime.core.node.message.Message;
import org.knime.filehandling.core.connections.FSFiles.LocalFileHandle;
import org.knime.filehandling.core.connections.FSPath;
import org.knime.filehandling.core.defaultnodesettings.status.StatusMessage;
import org.knime.geospatial.io.util.GeoFileNames;
import org.knime.geospatial.io.util.GeoFileNames.DetectedFormat;
import org.knime.geospatial.io.util.GeoTypeMapping;
import org.knime.geospatial.io.util.LocalFileStaging;
import org.knime.node.DefaultModel.ConfigureInput;
import org.knime.node.DefaultModel.ConfigureOutput;
import org.knime.node.DefaultModel.ExecuteInput;
import org.knime.node.DefaultModel.ExecuteOutput;
import org.knime.node.DefaultNode;
import org.knime.node.DefaultNodeFactory;
import org.knime.node.parameters.widget.file.FileSelectionConfig;

/**
 * Node factory for GeoFile Reader, 1:1 mirroring {@code GeoFileReaderNode} in the Python Geospatial Analytics
 * Extension's {@code knime_extension/src/nodes/io.py}.
 * <p>
 * KML/KMZ (partially supported by the Python node too, via GDAL's KML driver) and MapInfo TAB are not yet
 * implemented here — no GeoTools module for either was vendored into this bundle; both fail with a clear error
 * rather than silently producing wrong output. GeoParquet reading is likewise not yet implemented.
 */
public final class GeoFileReaderNodeFactory extends DefaultNodeFactory {

    private static final FileSelectionConfig FILE_SELECTION_CONFIG = FileSelectionConfig.builder().build();

    private static final DefaultNode NODE = DefaultNode.create() //
        .name("GeoFile Reader") //
        .icon("./GeoFileReader.png") //
        .shortDescription("""
                Read single layer GeoFile.
                """) //
        .fullDescription("""
                This node reads a single geospatial file from the provided local file path or URL. Supported
                formats: Shapefile (.shp), zipped Shapefile (.zip), single-layer GeoPackage (.gpkg), and GeoJSON
                (.geojson).
                """) //
        .sinceVersion(5, 13, 0) //
        .ports(p -> p //
            .addOutputTable("Geodata table", "Geodata from the input file.")) //
        .model(m -> m //
            .parametersClass(GeoFileReaderNodeParameters.class) //
            .configure(GeoFileReaderNodeFactory::configure) //
            .execute(GeoFileReaderNodeFactory::execute)) //
        .nodeType(NodeType.Source);

    /** Constructor used by the framework. */
    public GeoFileReaderNodeFactory() {
        super(NODE);
    }

    private static void configure(final ConfigureInput in, final ConfigureOutput out) throws InvalidSettingsException {
        // Format (and hence output spec) is only knowable once the file is actually read - same deferred-spec
        // pattern used by this codebase's other Gen3 readers (e.g. the SDF Reader).
        out.setOutSpec(0, null);
    }

    private static void execute(final ExecuteInput in, final ExecuteOutput out)
        throws CanceledExecutionException, KNIMEException {
        final var parameters = in.<GeoFileReaderNodeParameters> getParameters();
        final var exec = in.getExecutionContext();
        exec.setProgress(0.1, "Reading file (this might take a while without progress changes)");

        final Consumer<StatusMessage> statusConsumer = msg -> {
            if (msg.getType() == StatusMessage.MessageType.WARNING || msg.getType() == StatusMessage.MessageType.ERROR) {
                out.setWarningMessage(msg.getMessage());
            }
        };

        try (final var accessor = parameters.m_inputFile.getPathAccessor(FILE_SELECTION_CONFIG, Optional.empty())) {
            final List<FSPath> paths = accessor.getFSPaths(statusConsumer);
            final FSPath path = paths.get(0);
            final String fileName = path.getFileName().toString();
            final DetectedFormat format = GeoFileNames.detect(fileName);

            final BufferedDataTable table;
            if (format == DetectedFormat.KML || format == DetectedFormat.KMZ) {
                throw new KNIMEException("Reading " + format + " files is not yet implemented in this node.");
            } else if (format == DetectedFormat.PARQUET) {
                throw new KNIMEException("Reading GeoParquet files is not yet implemented in this node.");
            } else if (fileName.toLowerCase().endsWith(".geojson")) {
                table = readGeoJson(path, exec);
            } else if (fileName.toLowerCase().endsWith(".tab")) {
                throw new KNIMEException("Reading MapInfo TAB files is not yet implemented in this node.");
            } else if (fileName.toLowerCase().endsWith(".gpkg")) {
                table = readGeoPackageFirstLayer(path, exec);
            } else if (fileName.toLowerCase().endsWith(".zip")) {
                table = readZippedShapefile(path, exec);
            } else {
                // .shp and anything else GeoTools' shapefile reader can open directly
                table = readShapefile(path, exec);
            }
            out.setOutData(0, table);
        } catch (final CanceledExecutionException | KNIMEException e) {
            throw e;
        } catch (final Exception e) {
            throw KNIMEException.of(Message.fromSummary("Error reading file: " + e.getMessage()), e);
        }
    }

    private static BufferedDataTable readShapefile(final FSPath path, final org.knime.core.node.ExecutionContext exec)
        throws Exception {
        final LocalFileHandle local = LocalFileStaging.resolveExistingToLocalFile(path);
        try {
            final FileDataStore dataStore =
                new ShapefileDataStoreFactory().createDataStore(java.nio.file.Path.of(local.path()).toUri().toURL());
            try {
                final SimpleFeatureSource source = dataStore.getFeatureSource();
                return featureSourceToTable(source, exec);
            } finally {
                dataStore.dispose();
            }
        } finally {
            local.close();
        }
    }

    private static BufferedDataTable readZippedShapefile(final FSPath path,
        final org.knime.core.node.ExecutionContext exec) throws Exception {
        final LocalFileHandle local = LocalFileStaging.resolveExistingToLocalFile(path);
        try {
            final URL zipUrl = new URL("jar:" + java.nio.file.Path.of(local.path()).toUri().toURL() + "!/");
            final Map<String, Object> params = new HashMap<>();
            params.put(ShapefileDataStoreFactory.URLP.key, zipUrl);
            final DataStore dataStore = new ShapefileDataStoreFactory().createDataStore(params);
            try {
                final SimpleFeatureSource source =
                    (SimpleFeatureSource)dataStore.getFeatureSource(dataStore.getTypeNames()[0]);
                return featureSourceToTable(source, exec);
            } finally {
                dataStore.dispose();
            }
        } finally {
            local.close();
        }
    }

    private static BufferedDataTable readGeoJson(final FSPath path, final org.knime.core.node.ExecutionContext exec)
        throws Exception {
        try (InputStream in = Files.newInputStream(path)) {
            final FeatureCollection<SimpleFeatureType, SimpleFeature> features =
                new FeatureJSON().readFeatureCollection(in);
            return featureCollectionToTable(features, exec);
        }
    }

    private static BufferedDataTable readGeoPackageFirstLayer(final FSPath path,
        final org.knime.core.node.ExecutionContext exec) throws Exception {
        final LocalFileHandle local = LocalFileStaging.resolveExistingToLocalFile(path);
        try (final var geoPackage = new org.geotools.geopkg.GeoPackage(new java.io.File(local.path()))) {
            final var entries = geoPackage.features();
            if (entries.isEmpty()) {
                throw new KNIMEException("The GeoPackage file contains no feature layers.");
            }
            final var entry = entries.get(0);
            try (var reader = geoPackage.reader(entry, null, null)) {
                return featureReaderToTable(reader, exec);
            }
        } finally {
            local.close();
        }
    }

    private static BufferedDataTable featureSourceToTable(final SimpleFeatureSource source,
        final org.knime.core.node.ExecutionContext exec) throws Exception {
        final SimpleFeatureType featureType = source.getSchema();
        final DataTableSpec spec = GeoTypeMapping.toTableSpec(featureType);
        final int geoColIdx = geometryColumnIndex(featureType);
        final BufferedDataContainer container = exec.createDataContainer(spec, false);
        try (SimpleFeatureIterator it = source.getFeatures().features()) {
            long rowIdx = 0;
            while (it.hasNext()) {
                exec.checkCanceled();
                addFeatureRow(it.next(), spec, geoColIdx, container, rowIdx++);
            }
        }
        container.close();
        return container.getTable();
    }

    private static BufferedDataTable featureCollectionToTable(
        final FeatureCollection<SimpleFeatureType, SimpleFeature> features,
        final org.knime.core.node.ExecutionContext exec) throws Exception {
        final SimpleFeatureType featureType = features.getSchema();
        final DataTableSpec spec = GeoTypeMapping.toTableSpec(featureType);
        final int geoColIdx = geometryColumnIndex(featureType);
        final BufferedDataContainer container = exec.createDataContainer(spec, false);
        try (var it = features.features()) {
            long rowIdx = 0;
            while (it.hasNext()) {
                exec.checkCanceled();
                addFeatureRow(it.next(), spec, geoColIdx, container, rowIdx++);
            }
        }
        container.close();
        return container.getTable();
    }

    private static BufferedDataTable featureReaderToTable(final org.geotools.api.data.SimpleFeatureReader reader,
        final org.knime.core.node.ExecutionContext exec) throws Exception {
        final SimpleFeatureType featureType = reader.getFeatureType();
        final DataTableSpec spec = GeoTypeMapping.toTableSpec(featureType);
        final int geoColIdx = geometryColumnIndex(featureType);
        final BufferedDataContainer container = exec.createDataContainer(spec, false);
        long rowIdx = 0;
        while (reader.hasNext()) {
            exec.checkCanceled();
            addFeatureRow(reader.next(), spec, geoColIdx, container, rowIdx++);
        }
        container.close();
        return container.getTable();
    }

    private static int geometryColumnIndex(final SimpleFeatureType featureType) {
        return featureType.indexOf(featureType.getGeometryDescriptor().getLocalName());
    }

    private static void addFeatureRow(final SimpleFeature feature, final DataTableSpec spec, final int geoColIdx,
        final BufferedDataContainer container, final long rowIdx) throws KNIMEException {
        final DataCell[] cells = new DataCell[spec.getNumColumns()];
        for (int i = 0; i < cells.length; i++) {
            if (i == geoColIdx) {
                cells[i] = GeoTypeMapping.toGeoCell((org.locationtech.jts.geom.Geometry)feature.getAttribute(i),
                    feature.getFeatureType().getCoordinateReferenceSystem());
            } else {
                cells[i] = GeoTypeMapping.toDataCell(feature.getAttribute(i));
            }
        }
        final DataRow row = new DefaultRow(org.knime.core.data.RowKey.createRowKey(rowIdx), cells);
        container.addRowToTable(row);
    }
}

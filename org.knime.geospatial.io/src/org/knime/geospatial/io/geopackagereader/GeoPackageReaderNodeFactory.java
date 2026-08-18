/*
 * ------------------------------------------------------------------------
 *
 *  Copyright by KNIME AG, Zurich, Switzerland
 *  Website: http://www.knime.com; Email: contact@knime.com
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License, Version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, see <http://www.gnu.org/licenses>.
 *
 *  Additional permission under GNU GPL version 3 section 7:
 *
 *  KNIME interoperates with ECLIPSE solely via ECLIPSE's plug-in APIs.
 *  Hence, KNIME and ECLIPSE are both independent programs and are not
 *  derived from each other. Should, however, the interpretation of the
 *  GNU GPL Version 3 ("License") under any applicable laws result in
 *  KNIME and ECLIPSE being a combined program, KNIME AG herewith grants
 *  you the additional permission to use and propagate KNIME together with
 *  ECLIPSE with only the license terms in place for ECLIPSE applying to
 *  ECLIPSE and the GNU GPL Version 3 applying for KNIME, provided the
 *  license terms of ECLIPSE themselves allow for the respective use and
 *  propagation of ECLIPSE together with KNIME.
 *
 *  Additional permission relating to nodes for KNIME that extend the Node
 *  Extension (and in particular that are based on subclasses of NodeModel,
 *  NodeDialog, and NodeView) and that only interoperate with KNIME through
 *  standard APIs ("Nodes"):
 *  Nodes are deemed to be separate and independent programs and to not be
 *  covered works.  Notwithstanding anything to the contrary in the
 *  License, the License does not apply to Nodes, you are not required to
 *  license Nodes under the License, and you are granted a license to
 *  prepare and propagate Nodes, in each case even if such Nodes are
 *  propagated with or for interoperation with KNIME.  The owner of a Node
 *  may freely choose the license terms applicable to such Node, including
 *  when such Node is propagated with or for interoperation with KNIME.
 * ---------------------------------------------------------------------
 *
 * History
 *   19 May 2026 (AI): created
 */
package org.knime.geospatial.io.geopackagereader;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.geotools.api.data.SimpleFeatureReader;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.geopkg.FeatureEntry;
import org.geotools.geopkg.GeoPackage;
import org.knime.core.data.DataCell;
import org.knime.core.data.DataRow;
import org.knime.core.data.DataTableSpec;
import org.knime.core.data.DataType;
import org.knime.core.data.RowKey;
import org.knime.core.data.def.DefaultRow;
import org.knime.core.data.def.StringCell;
import org.knime.core.node.BufferedDataContainer;
import org.knime.core.node.BufferedDataTable;
import org.knime.core.node.CanceledExecutionException;
import org.knime.core.node.ExecutionContext;
import org.knime.core.node.InvalidSettingsException;
import org.knime.core.node.KNIMEException;
import org.knime.core.node.message.Message;
import org.knime.filehandling.core.connections.FSFiles.LocalFileHandle;
import org.knime.filehandling.core.connections.FSPath;
import org.knime.filehandling.core.defaultnodesettings.status.StatusMessage;
import org.knime.geospatial.io.util.GeoFileEncoding;
import org.knime.geospatial.io.util.GeoPackageTextEncoding;
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
 * Node factory for GeoPackage Reader, 1:1 mirroring {@code GeoPackageReaderNode} in the Python Geospatial Analytics
 * Extension's {@code knime_extension/src/nodes/io.py}: reads a chosen layer (by name, or numeric index if the
 * configured value isn't a layer name, falling back to the first layer) plus a second output table listing all
 * layer names.
 */
public final class GeoPackageReaderNodeFactory extends DefaultNodeFactory {

    private static final FileSelectionConfig FILE_SELECTION_CONFIG = FileSelectionConfig.builder().build();

    private static final DefaultNode NODE = DefaultNode.create() //
        .name("GeoPackage Reader (Java)") //
        .icon("./GeoPackageReader.png") //
        .shortDescription("""
                Read GeoPackage layer
                """) //
        .fullDescription("""
                This node reads a GeoPackage file. You can specify the layer to read by name or by numeric index
                (starting at 0); if the layer is empty or does not match, the first layer is read. The node also
                outputs the names of all layers as a second output table.
                """) //
        .sinceVersion(5, 13, 0) //
        .ports(p -> p //
            .addOutputTable("Geodata table", "Geodata from the input file path.") //
            .addOutputTable("Geodata Layer", "Layer information from the input file path.")) //
        .model(m -> m //
            .parametersClass(GeoPackageReaderNodeParameters.class) //
            .configure(GeoPackageReaderNodeFactory::configure) //
            .execute(GeoPackageReaderNodeFactory::execute)) //
        .nodeType(NodeType.Source);

    public GeoPackageReaderNodeFactory() {
        super(NODE);
    }

    private static void configure(final ConfigureInput in, final ConfigureOutput out) throws InvalidSettingsException {
        // Layer schema is only knowable once the file is actually read.
        out.setOutSpec(0, null);
        out.setOutSpec(1, null);
    }

    private static void execute(final ExecuteInput in, final ExecuteOutput out)
        throws CanceledExecutionException, KNIMEException {
        final var parameters = in.<GeoPackageReaderNodeParameters> getParameters();
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
            final LocalFileHandle local = LocalFileStaging.resolveExistingToLocalFile(path);
            try (var geoPackage = new GeoPackage(new java.io.File(local.path()))) {
                final List<FeatureEntry> entries = geoPackage.features();
                if (entries.isEmpty()) {
                    throw new KNIMEException("The GeoPackage file contains no feature layers.");
                }

                final BufferedDataTable layerListTable = buildLayerListTable(entries, exec);
                final FeatureEntry entry = resolveLayer(entries, parameters.m_layer);

                try (SimpleFeatureReader reader = geoPackage.reader(entry, null, null)) {
                    final BufferedDataTable dataTable = readFeatures(reader, exec, Path.of(local.path()),
                        entry.getTableName(), parameters.m_encoding);
                    out.setOutData(0, dataTable);
                    out.setOutData(1, layerListTable);
                }
            } finally {
                local.close();
            }
        } catch (final CanceledExecutionException | KNIMEException e) {
            throw e;
        } catch (final Exception e) {
            throw KNIMEException.of(Message.fromSummary("Error reading file: " + e.getMessage()), e);
        }
    }

    /**
     * Mirrors the Python node's {@code _get_layer}: an exact name match wins, else a numeric index (0-99) if the
     * configured value is all-digits and in range, else the first layer.
     */
    private static FeatureEntry resolveLayer(final List<FeatureEntry> entries, final String configuredLayer) {
        for (final FeatureEntry entry : entries) {
            if (entry.getTableName().equals(configuredLayer)) {
                return entry;
            }
        }
        if (configuredLayer != null && configuredLayer.matches("\\d+")) {
            final int index = Integer.parseInt(configuredLayer);
            if (index >= 0 && index < 100 && index < entries.size()) {
                return entries.get(index);
            }
        }
        return entries.get(0);
    }

    private static BufferedDataTable buildLayerListTable(final List<FeatureEntry> entries, final ExecutionContext exec) {
        final DataTableSpec spec =
            new DataTableSpec(new org.knime.core.data.DataColumnSpecCreator("layerlist", StringCell.TYPE).createSpec());
        final BufferedDataContainer container = exec.createDataContainer(spec, false);
        long rowIdx = 0;
        for (final FeatureEntry entry : entries) {
            container.addRowToTable(
                new DefaultRow(RowKey.createRowKey(rowIdx++), new StringCell(entry.getTableName())));
        }
        container.close();
        return container.getTable();
    }

    private static BufferedDataTable readFeatures(final SimpleFeatureReader reader, final ExecutionContext exec,
        final Path localFile, final String layerName, final GeoFileEncoding encoding)
        throws CanceledExecutionException, KNIMEException, SQLException {
        final SimpleFeatureType featureType = reader.getFeatureType();
        final DataTableSpec spec = GeoTypeMapping.toTableSpec(featureType);
        final int geoColIdx = featureType.indexOf(featureType.getGeometryDescriptor().getLocalName());

        // Non-AUTO encodings bypass GeoTools'/the JDBC driver's own UTF-8-only text decoding entirely for TEXT
        // columns - see GeoPackageTextEncoding's javadoc for why, and the interoperability tradeoff of doing so.
        final Optional<Charset> targetCharset = encoding.toCharset();
        final List<String> textColumns = new ArrayList<>();
        if (targetCharset.isPresent()) {
            for (final var attr : featureType.getAttributeDescriptors()) {
                if (attr.getType().getBinding() == String.class) {
                    textColumns.add(attr.getLocalName());
                }
            }
        }
        final Map<Long, Map<String, String>> rawTextByFid;
        try {
            rawTextByFid = targetCharset.isEmpty() ? Map.of()
                : GeoPackageTextEncoding.readRaw(localFile, layerName, textColumns, targetCharset.get());
        } catch (final java.nio.charset.CharacterCodingException e) {
            throw KNIMEException.of(Message.fromSummary("Layer \"" + layerName + "\" does not contain valid "
                + targetCharset.get() + " text - check whether the Encoding setting matches how this file was "
                + "actually written."), e);
        }

        final BufferedDataContainer container = exec.createDataContainer(spec, false);
        long rowIdx = 0;
        try {
            while (reader.hasNext()) {
                exec.checkCanceled();
                final SimpleFeature feature = reader.next();
                final Map<String, String> rowOverride = rawTextByFid
                    .get(GeoPackageTextEncoding.Writer.parseFid(feature.getID()));
                final DataCell[] cells = new DataCell[spec.getNumColumns()];
                for (int i = 0; i < cells.length; i++) {
                    if (i == geoColIdx) {
                        cells[i] = GeoTypeMapping.toGeoCell(
                            (org.locationtech.jts.geom.Geometry)feature.getAttribute(i),
                            featureType.getCoordinateReferenceSystem());
                    } else {
                        final String columnName = spec.getColumnSpec(i).getName();
                        if (rowOverride != null && rowOverride.containsKey(columnName)) {
                            final String overridden = rowOverride.get(columnName);
                            cells[i] = overridden == null ? DataType.getMissingCell() : new StringCell(overridden);
                        } else {
                            cells[i] = GeoTypeMapping.toDataCell(feature.getAttribute(i));
                        }
                    }
                }
                final DataRow row = new DefaultRow(RowKey.createRowKey(rowIdx++), cells);
                container.addRowToTable(row);
            }
        } catch (final java.io.IOException e) {
            throw KNIMEException.of(Message.fromSummary("Error reading features: " + e.getMessage()), e);
        }
        container.close();
        return container.getTable();
    }
}

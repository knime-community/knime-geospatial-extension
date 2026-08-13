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
package org.knime.geospatial.io.geofilewriter;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.geotools.api.data.DataStore;
import org.geotools.api.data.SimpleFeatureStore;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.data.collection.ListFeatureCollection;
import org.geotools.data.shapefile.ShapefileDataStoreFactory;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.geojson.feature.FeatureJSON;
import org.knime.core.data.DataRow;
import org.knime.core.data.DataTableSpec;
import org.knime.core.node.BufferedDataTable;
import org.knime.core.node.CanceledExecutionException;
import org.knime.core.node.InvalidSettingsException;
import org.knime.core.node.KNIMEException;
import org.knime.core.node.message.Message;
import org.knime.filehandling.core.connections.FSFiles;
import org.knime.filehandling.core.connections.FSPath;
import org.knime.filehandling.core.defaultnodesettings.status.StatusMessage;
import org.knime.geospatial.core.data.GeoValue;
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
 * Node factory for GeoFile Writer, 1:1 mirroring {@code GeoFileWriterNode} in the Python Geospatial Analytics
 * Extension's {@code knime_extension/src/nodes/io.py}.
 */
public final class GeoFileWriterNodeFactory extends DefaultNodeFactory {

    private static final FileSelectionConfig FILE_SELECTION_CONFIG = FileSelectionConfig.builder().build();

    private static final DefaultNode NODE = DefaultNode.create() //
        .name("GeoFile Writer (Java)") //
        .icon("./GeoFileWriter.png") //
        .shortDescription("""
                Write single layer GeoFile.
                """) //
        .fullDescription("""
                This node writes the data in the format of Shapefile, GeoJSON, GeoParquet, or GML. The file
                extension is appended automatically depending on the selected file format if not specified.
                """) //
        .sinceVersion(5, 13, 0) //
        .ports(p -> p //
            .addInputTable("Geodata table", "Geodata from the input port.")) //
        .model(m -> m //
            .parametersClass(GeoFileWriterNodeParameters.class) //
            .configure(GeoFileWriterNodeFactory::configure) //
            .execute(GeoFileWriterNodeFactory::execute)) //
        .nodeType(NodeType.Sink);

    public GeoFileWriterNodeFactory() {
        super(NODE);
    }

    private static void configure(final ConfigureInput in, final ConfigureOutput out) throws InvalidSettingsException {
        final var parameters = in.<GeoFileWriterNodeParameters> getParameters();
        final DataTableSpec inSpec = in.getInTableSpec(0);
        if (parameters.m_geoColumn == null || !inSpec.containsName(parameters.m_geoColumn)
            || !inSpec.getColumnSpec(parameters.m_geoColumn).getType().isCompatible(GeoValue.class)) {
            throw new InvalidSettingsException("No valid geometry column selected");
        }
        parameters.m_outputFile.validateOnConfigure(FILE_SELECTION_CONFIG, Optional.empty(), out::setWarningMessage);
    }

    private static void execute(final ExecuteInput in, final ExecuteOutput out)
        throws CanceledExecutionException, KNIMEException {
        final var parameters = in.<GeoFileWriterNodeParameters> getParameters();
        final BufferedDataTable table = in.getInTable(0);
        final DataTableSpec spec = table.getDataTableSpec();
        final int geoColIdx = spec.findColumnIndex(parameters.m_geoColumn);

        final String extension = switch (parameters.m_format) {
            case SHAPEFILE -> ".shp";
            case GEOJSON -> ".geojson";
            case GEOPARQUET -> ".parquet";
            case GML -> ".gml";
        };

        final Consumer<StatusMessage> statusConsumer = msg -> {
            if (msg.getType() == StatusMessage.MessageType.WARNING || msg.getType() == StatusMessage.MessageType.ERROR) {
                out.setWarningMessage(msg.getMessage());
            }
        };

        try (final var accessor = parameters.m_outputFile.getPathAccessor(FILE_SELECTION_CONFIG, Optional.empty())) {
            final FSPath rawDestPath = accessor.getOutputPath(statusConsumer);
            final FSPath destPath = (FSPath)rawDestPath
                .resolveSibling(GeoFileNames.ensureExtension(rawDestPath.getFileName().toString(), extension));

            final var openOptions = switch (parameters.m_overwritePolicy) {
                case OVERWRITE -> new java.nio.file.OpenOption[]{StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING};
                case FAIL -> new java.nio.file.OpenOption[]{StandardOpenOption.CREATE_NEW};
            };

            final SimpleFeatureType featureType = buildFeatureType(spec, geoColIdx,
                table.size() == 0 ? null : GeoTypeMapping.findCrs(table, geoColIdx));
            final ListFeatureCollection features = buildFeatureCollection(featureType, table, geoColIdx, in);

            switch (parameters.m_format) {
                case SHAPEFILE -> writeShapefile(features, destPath, parameters, openOptions);
                case GEOJSON -> writeGeoJson(features, destPath, openOptions);
                case GEOPARQUET, GML ->
                    throw new KNIMEException("Writing " + parameters.m_format + " is not yet implemented in this node.");
            }
        } catch (final CanceledExecutionException e) {
            throw e;
        } catch (final KNIMEException e) {
            throw e;
        } catch (final Exception e) {
            throw KNIMEException.of(Message.fromSummary("Error writing file: " + e.getMessage()), e);
        }
    }

    private static SimpleFeatureType buildFeatureType(final DataTableSpec spec, final int geoColIdx,
        final org.geotools.api.referencing.crs.CoordinateReferenceSystem crs) {
        final var builder = new SimpleFeatureTypeBuilder();
        builder.setName(spec.getColumnSpec(geoColIdx).getName());
        for (int i = 0; i < spec.getNumColumns(); i++) {
            final String name = spec.getColumnSpec(i).getName();
            if (i == geoColIdx) {
                builder.add(name, Geometry.class, crs);
            } else {
                builder.add(name, String.class);
            }
        }
        return builder.buildFeatureType();
    }

    private static ListFeatureCollection buildFeatureCollection(final SimpleFeatureType featureType,
        final BufferedDataTable table, final int geoColIdx, final ExecuteInput in)
        throws KNIMEException, CanceledExecutionException {
        final var features = new ListFeatureCollection(featureType);
        final var exec = in.getExecutionContext();
        long rowIdx = 0;
        final long rowCount = table.size();
        for (final DataRow row : table) {
            exec.checkCanceled();
            if (rowCount > 0) {
                exec.setProgress((double)rowIdx / rowCount, "Writing row " + rowIdx + "/" + rowCount);
            }
            final var builder = new SimpleFeatureBuilder(featureType);
            for (int i = 0; i < row.getNumCells(); i++) {
                if (i == geoColIdx) {
                    final var cell = row.getCell(i);
                    builder.add(cell.isMissing() ? null : GeoTypeMapping.toJtsGeometry((GeoValue)cell));
                } else {
                    final var cell = row.getCell(i);
                    builder.add(cell.isMissing() ? null : cell.toString());
                }
            }
            features.add(builder.buildFeature(row.getKey().toString()));
            rowIdx++;
        }
        return features;
    }

    private static void writeShapefile(final ListFeatureCollection features, final FSPath destPath,
        final GeoFileWriterNodeParameters parameters, final java.nio.file.OpenOption[] openOptions)
        throws IOException {
        final Path localDir = LocalFileStaging.createLocalStagingDir("geofilewriter-shp-");
        try {
            final Path localShp = localDir.resolve(destPath.getFileName().toString());
            final Map<String, Object> params = new HashMap<>();
            params.put(ShapefileDataStoreFactory.URLP.key, localShp.toUri().toURL());
            parameters.m_encoding.toCharset()
                .ifPresent(cs -> params.put(ShapefileDataStoreFactory.DBFCHARSET.key, cs.name()));
            final DataStore dataStore = new ShapefileDataStoreFactory().createNewDataStore(params);
            dataStore.createSchema(features.getSchema());
            final var featureStore = (SimpleFeatureStore)dataStore.getFeatureSource(dataStore.getTypeNames()[0]);
            featureStore.addFeatures(features);
            dataStore.dispose();
            LocalFileStaging.uploadDirectoryTo(localDir, destPath, openOptions);
        } finally {
            LocalFileStaging.deleteDirectoryQuietly(localDir);
        }
    }

    private static void writeGeoJson(final ListFeatureCollection features, final FSPath destPath,
        final java.nio.file.OpenOption[] openOptions) throws IOException {
        try (var out = FSFiles.newOutputStream(destPath, openOptions)) {
            new FeatureJSON().writeFeatureCollection(features, out);
        }
    }
}

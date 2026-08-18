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

import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.io.api.Binary;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Types;
import org.geotools.api.data.DataStore;
import org.geotools.api.data.SimpleFeatureStore;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.data.collection.ListFeatureCollection;
import org.geotools.data.shapefile.ShapefileDataStoreFactory;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.geojson.feature.FeatureJSON;
import org.geotools.referencing.CRS;
import org.knime.core.data.BooleanValue;
import org.knime.core.data.DataCell;
import org.knime.core.data.DataRow;
import org.knime.core.data.DataTableSpec;
import org.knime.core.data.DataType;
import org.knime.core.data.DoubleValue;
import org.knime.core.data.IntValue;
import org.knime.core.data.LongValue;
import org.knime.core.data.def.BooleanCell;
import org.knime.core.data.def.DoubleCell;
import org.knime.core.data.def.IntCell;
import org.knime.core.data.def.LongCell;
import org.knime.core.node.BufferedDataTable;
import org.knime.core.node.CanceledExecutionException;
import org.knime.core.node.ExecutionContext;
import org.knime.core.node.InvalidSettingsException;
import org.knime.core.node.KNIMEException;
import org.knime.core.node.message.Message;
import org.knime.filehandling.core.connections.FSFiles;
import org.knime.filehandling.core.connections.FSPath;
import org.knime.filehandling.core.defaultnodesettings.status.StatusMessage;
import org.knime.geospatial.core.data.GeoValue;
import org.knime.geospatial.io.util.GeoFileNames;
import org.knime.geospatial.io.util.GeoTypeMapping;
import org.knime.geospatial.io.util.HadoopFreeParquetCodecFactory;
import org.knime.geospatial.io.util.LocalFileStaging;
import org.knime.node.DefaultModel.ConfigureInput;
import org.knime.node.DefaultModel.ConfigureOutput;
import org.knime.node.DefaultModel.ExecuteInput;
import org.knime.node.DefaultModel.ExecuteOutput;
import org.knime.node.DefaultNode;
import org.knime.node.DefaultNodeFactory;
import org.knime.node.parameters.widget.file.FileSelectionConfig;
import org.locationtech.jts.geom.Geometry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
            case GEOPARQUET -> switch (parameters.m_parquetCompression) {
                case NONE -> ".parquet";
                case BROTLI -> ".parquet.br";
                case GZIP -> ".parquet.gz";
                case SNAPPY -> ".parquet.snappy";
            };
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

            if (parameters.m_overwritePolicy == GeoFileWriterNodeParameters.ExistingFile.FAIL
                && java.nio.file.Files.exists(destPath)) {
                throw new KNIMEException(
                    "Output file \"" + destPath + "\" already exists - must not overwrite as per user setting");
            }

            final var openOptions = switch (parameters.m_overwritePolicy) {
                case OVERWRITE -> new java.nio.file.OpenOption[]{StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING};
                case FAIL -> new java.nio.file.OpenOption[]{StandardOpenOption.CREATE_NEW};
            };

            switch (parameters.m_format) {
                case SHAPEFILE, GEOJSON, GML -> {
                    final SimpleFeatureType featureType =
                        buildFeatureType(spec, geoColIdx, GeoTypeMapping.findCrs(table, geoColIdx));
                    final ListFeatureCollection features = buildFeatureCollection(featureType, table, geoColIdx, in);
                    switch (parameters.m_format) {
                        case SHAPEFILE -> writeShapefile(features, destPath, parameters, openOptions);
                        case GEOJSON -> writeGeoJson(features, destPath, openOptions);
                        case GML -> writeGml(features, destPath, parameters, openOptions);
                        default -> throw new IllegalStateException(); // unreachable - outer switch already narrowed
                    }
                }
                case GEOPARQUET -> writeGeoParquet(table, spec, geoColIdx, destPath, parameters, in);
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
                builder.add(name, GeoTypeMapping.javaTypeFor(spec.getColumnSpec(i).getType()));
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
                    builder.add(GeoTypeMapping.toJavaValue(row.getCell(i)));
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

    private static void writeGml(final ListFeatureCollection features, final FSPath destPath,
        final GeoFileWriterNodeParameters parameters, final java.nio.file.OpenOption[] openOptions)
        throws IOException {
        final var encoder = new org.geotools.xsd.Encoder(new org.geotools.gml3.GMLConfiguration());
        parameters.m_encoding.toCharset().ifPresent(encoder::setEncoding);
        encoder.setIndenting(true);
        encoder.getNamespaces().declarePrefix(features.getSchema().getTypeName(),
            "http://" + features.getSchema().getTypeName());
        try (var out = FSFiles.newOutputStream(destPath, openOptions)) {
            encoder.encode(features, org.geotools.gml3.GML.featureMembers, out);
        }
    }

    /**
     * Writes a GeoParquet file via Parquet's own {@code ExampleParquetWriter}/{@code Group} API rather than through
     * a GeoTools {@code SimpleFeatureType} - GeoTools has no Parquet module at all, mirroring the GeoFile Reader's
     * own hand-rolled Parquet read path. The geometry column is stored as raw WKB bytes (no JTS round-trip needed)
     * and the file-level {@code "geo"} key-value metadata is written by hand per the GeoParquet spec, the reverse of
     * {@code GeoFileReaderNodeFactory.extractPrimaryGeometryColumn}/{@code extractCrs}.
     */
    private static void writeGeoParquet(final BufferedDataTable table, final DataTableSpec spec, final int geoColIdx,
        final FSPath destPath, final GeoFileWriterNodeParameters parameters, final ExecuteInput in)
        throws IOException, KNIMEException, CanceledExecutionException {
        final CompressionCodecName codec = switch (parameters.m_parquetCompression) {
            case NONE -> CompressionCodecName.UNCOMPRESSED;
            case BROTLI -> CompressionCodecName.BROTLI;
            case GZIP -> CompressionCodecName.GZIP;
            case SNAPPY -> CompressionCodecName.SNAPPY;
        };

        final MessageType schema = buildParquetSchema(spec, geoColIdx);
        final String geoMetaJson =
            buildGeoParquetMetadata(spec.getColumnSpec(geoColIdx).getName(), GeoTypeMapping.findCrs(table, geoColIdx));

        final Path localFile = LocalFileStaging.createLocalStagingFile("geofilewriter-parquet-", ".parquet");
        try {
            try (ParquetWriter<Group> writer = ExampleParquetWriter.builder(new LocalOutputFile(localFile)) //
                .withType(schema) //
                .withConf(new PlainParquetConfiguration()) //
                .withCompressionCodec(codec) //
                .withCodecFactory(HadoopFreeParquetCodecFactory.INSTANCE) //
                .withExtraMetaData(Map.of("geo", geoMetaJson)) //
                .build()) {
                final SimpleGroupFactory groupFactory = new SimpleGroupFactory(schema);
                final ExecutionContext exec = in.getExecutionContext();
                long rowIdx = 0;
                final long rowCount = table.size();
                for (final DataRow row : table) {
                    exec.checkCanceled();
                    if (rowCount > 0) {
                        exec.setProgress((double)rowIdx / rowCount, "Writing row " + rowIdx + "/" + rowCount);
                    }
                    writer.write(buildParquetGroup(groupFactory, row, spec, geoColIdx));
                    rowIdx++;
                }
            }
            final var openOptions =
                new java.nio.file.OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING};
            LocalFileStaging.uploadFileTo(localFile, destPath, openOptions);
        } finally {
            LocalFileStaging.deleteQuietly(localFile);
        }
    }

    /** All columns are OPTIONAL - a KNIME cell (including the geometry column's) can be missing in any row. */
    private static MessageType buildParquetSchema(final DataTableSpec spec, final int geoColIdx) {
        final var builder = Types.buildMessage();
        for (int i = 0; i < spec.getNumColumns(); i++) {
            final String name = spec.getColumnSpec(i).getName();
            final PrimitiveTypeName type = i == geoColIdx ? PrimitiveTypeName.BINARY
                : parquetTypeFor(spec.getColumnSpec(i).getType());
            builder.optional(type).named(name);
        }
        return builder.named("feature");
    }

    /** Reverse of {@code GeoFileReaderNodeFactory.parquetTypeToDataType}. */
    private static PrimitiveTypeName parquetTypeFor(final DataType type) {
        if (type.equals(IntCell.TYPE)) {
            return PrimitiveTypeName.INT32;
        } else if (type.equals(LongCell.TYPE)) {
            return PrimitiveTypeName.INT64;
        } else if (type.equals(DoubleCell.TYPE)) {
            return PrimitiveTypeName.DOUBLE;
        } else if (type.equals(BooleanCell.TYPE)) {
            return PrimitiveTypeName.BOOLEAN;
        }
        return PrimitiveTypeName.BINARY;
    }

    private static Group buildParquetGroup(final SimpleGroupFactory groupFactory, final DataRow row,
        final DataTableSpec spec, final int geoColIdx) throws KNIMEException {
        final Group group = groupFactory.newGroup();
        for (int i = 0; i < row.getNumCells(); i++) {
            final DataCell cell = row.getCell(i);
            if (cell.isMissing()) {
                continue;
            }
            final String name = spec.getColumnSpec(i).getName();
            if (i == geoColIdx) {
                group.append(name, Binary.fromConstantByteArray(((GeoValue)cell).getWKB()));
            } else if (cell instanceof IntValue iv) {
                group.append(name, iv.getIntValue());
            } else if (cell instanceof LongValue lv) {
                group.append(name, lv.getLongValue());
            } else if (cell instanceof DoubleValue dv) {
                group.append(name, dv.getDoubleValue());
            } else if (cell instanceof BooleanValue bv) {
                group.append(name, bv.getBooleanValue());
            } else {
                group.append(name, cell.toString());
            }
        }
        return group;
    }

    /**
     * Builds the GeoParquet spec's file-level {@code "geo"} metadata JSON by hand (no library implements the spec).
     * {@code geometry_types} is left empty ("not specified" per spec, valid since computing the exact per-row
     * geometry type union is unnecessary extra work here). The {@code crs} entry is a minimal, non-fully-PROJJSON-
     * compliant {@code {"id": {"authority", "code"}}} object - a full PROJJSON document would require deriving the
     * complete CRS definition (datum, name, ...), which is out of scope; this is enough for this node's own reader
     * (mirrors {@code GeoFileReaderNodeFactory.extractCrs}, which likewise only looks at a top-level {@code id}).
     * Omitted entirely when the CRS is unset or is the spec's own default (OGC:CRS84 / EPSG:4326).
     */
    private static String buildGeoParquetMetadata(final String geometryColumn, final CoordinateReferenceSystem crs) {
        final ObjectMapper mapper = new ObjectMapper();
        final ObjectNode root = mapper.createObjectNode();
        root.put("version", "1.0.0");
        root.put("primary_column", geometryColumn);
        final ObjectNode columns = mapper.createObjectNode();
        final ObjectNode geomColumn = mapper.createObjectNode();
        geomColumn.put("encoding", "WKB");
        geomColumn.putArray("geometry_types");

        final String identifier = crs == null ? null : lookupCrsIdentifier(crs);
        if (identifier != null && !identifier.equals("EPSG:4326") && !identifier.equals("OGC:CRS84")) {
            final int colon = identifier.indexOf(':');
            final ObjectNode crsNode = mapper.createObjectNode();
            final ObjectNode id = mapper.createObjectNode();
            if (colon < 0) {
                id.put("authority", identifier);
            } else {
                id.put("authority", identifier.substring(0, colon));
                final String code = identifier.substring(colon + 1);
                try {
                    id.put("code", Integer.parseInt(code));
                } catch (final NumberFormatException e) { // NOSONAR - non-numeric codes are written as strings
                    id.put("code", code);
                }
            }
            crsNode.set("id", id);
            geomColumn.set("crs", crsNode);
        }
        columns.set(geometryColumn, geomColumn);
        root.set("columns", columns);
        return root.toString();
    }

    private static String lookupCrsIdentifier(final CoordinateReferenceSystem crs) {
        try {
            return CRS.lookupIdentifier(crs, true);
        } catch (final Exception e) { // NOSONAR - falling back to "no identifier" is correct on any lookup failure
            return null;
        }
    }
}

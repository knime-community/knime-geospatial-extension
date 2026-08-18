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
package org.knime.geospatial.io.geofilereader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.parquet.ParquetReadOptions;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.api.ReadSupport;
import org.apache.parquet.hadoop.example.GroupReadSupport;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.Type;
import org.geotools.api.data.DataStore;
import org.geotools.api.data.SimpleFeatureSource;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.data.shapefile.ShapefileDataStoreFactory;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.feature.FeatureCollection;
import org.geotools.geojson.feature.FeatureJSON;
import org.knime.core.data.DataCell;
import org.knime.core.data.DataColumnSpec;
import org.knime.core.data.DataColumnSpecCreator;
import org.knime.core.data.DataRow;
import org.knime.core.data.DataTableSpec;
import org.knime.core.data.DataType;
import org.knime.core.data.RowKey;
import org.knime.core.data.def.BooleanCell;
import org.knime.core.data.def.DefaultRow;
import org.knime.core.data.def.DoubleCell;
import org.knime.core.data.def.IntCell;
import org.knime.core.data.def.LongCell;
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
import org.knime.geospatial.core.data.cell.GeoCell;
import org.knime.geospatial.core.data.cell.GeoCellFactory;
import org.knime.geospatial.core.data.reference.GeoReferenceSystem;
import org.knime.geospatial.core.data.reference.GeoReferenceSystemFactory;
import org.knime.geospatial.io.util.GeoFileEncoding;
import org.knime.geospatial.io.util.GeoFileNames;
import org.knime.geospatial.io.util.GeoFileNames.DetectedFormat;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Node factory for GeoFile Reader, 1:1 mirroring {@code GeoFileReaderNode} in the Python Geospatial Analytics
 * Extension's {@code knime_extension/src/nodes/io.py}.
 * <p>
 * KML/KMZ is read via {@link KmlReader}, a hand-rolled parser matching GDAL's OGR KML driver's exact schema (the
 * library the Python node reads KML/KMZ through) rather than GeoTools' own KML binding, whose output is structurally
 * different and silently drops {@code ExtendedData}. MapInfo TAB is not implemented — no GeoTools module for it
 * exists at all (confirmed absent from the OSGeo Maven repository for this GeoTools version) — and fails with a
 * clear error rather than silently producing wrong output.
 */
public final class GeoFileReaderNodeFactory extends DefaultNodeFactory {

    private static final FileSelectionConfig FILE_SELECTION_CONFIG = FileSelectionConfig.builder().build();

    private static final DefaultNode NODE = DefaultNode.create() //
        .name("GeoFile Reader (Java)") //
        .icon("./GeoFileReader.png") //
        .shortDescription("""
                Read single layer GeoFile.
                """) //
        .fullDescription("""
                This node reads a single geospatial file from the provided local file path or URL. Supported
                formats: Shapefile (.shp), zipped Shapefile (.zip), single-layer GeoPackage (.gpkg), GeoJSON
                (.geojson), GeoParquet (.parquet, optionally .br/.gz/.snappy-compressed), and KML/KMZ (.kml/.kmz,
                single kml entry only). MapInfo TAB (.tab) is not supported.
                """) //
        .sinceVersion(5, 13, 0) //
        .ports(p -> p //
            .addOutputTable("Geodata table", "Geodata from the input file.")) //
        .model(m -> m //
            .parametersClass(GeoFileReaderNodeParameters.class) //
            .configure(GeoFileReaderNodeFactory::configure) //
            .execute(GeoFileReaderNodeFactory::execute)) //
        .nodeType(NodeType.Source);

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
            if (format == DetectedFormat.KML) {
                try (InputStream kmlIn = Files.newInputStream(path)) {
                    table = KmlReader.read(kmlIn, exec);
                }
            } else if (format == DetectedFormat.KMZ) {
                table = readKmz(path, exec);
            } else if (format == DetectedFormat.PARQUET) {
                table = readGeoParquet(path, exec);
            } else if (fileName.toLowerCase().endsWith(".geojson")) {
                table = readGeoJson(path, exec);
            } else if (fileName.toLowerCase().endsWith(".tab")) {
                throw new KNIMEException("Reading MapInfo TAB files is not yet implemented in this node.");
            } else if (fileName.toLowerCase().endsWith(".gpkg")) {
                table = readGeoPackageFirstLayer(path, exec);
            } else if (fileName.toLowerCase().endsWith(".zip")) {
                table = readZippedShapefile(path, exec, parameters.m_encoding);
            } else {
                // .shp and anything else GeoTools' shapefile reader can open directly
                table = readShapefile(path, exec, parameters.m_encoding);
            }
            out.setOutData(0, table);
        } catch (final CanceledExecutionException | KNIMEException e) {
            throw e;
        } catch (final Exception e) {
            throw KNIMEException.of(Message.fromSummary("Error reading file: " + e.getMessage()), e);
        }
    }

    private static BufferedDataTable readShapefile(final FSPath path, final org.knime.core.node.ExecutionContext exec,
        final GeoFileEncoding encoding) throws Exception {
        final LocalFileHandle local = LocalFileStaging.resolveExistingToLocalFile(path);
        try {
            final Map<String, Object> params = new HashMap<>();
            params.put(ShapefileDataStoreFactory.URLP.key,
                java.nio.file.Path.of(local.path()).toUri().toURL());
            encoding.toCharset().ifPresent(cs -> params.put(ShapefileDataStoreFactory.DBFCHARSET.key, cs.name()));
            final DataStore dataStore = new ShapefileDataStoreFactory().createDataStore(params);
            try {
                final SimpleFeatureSource source = dataStore.getFeatureSource(dataStore.getTypeNames()[0]);
                return featureSourceToTable(source, exec);
            } finally {
                dataStore.dispose();
            }
        } finally {
            local.close();
        }
    }

    private static BufferedDataTable readZippedShapefile(final FSPath path,
        final org.knime.core.node.ExecutionContext exec, final GeoFileEncoding encoding)
        throws Exception {
        final LocalFileHandle local = LocalFileStaging.resolveExistingToLocalFile(path);
        try {
            final URL zipUrl = new URL("jar:" + java.nio.file.Path.of(local.path()).toUri().toURL() + "!/");
            final Map<String, Object> params = new HashMap<>();
            params.put(ShapefileDataStoreFactory.URLP.key, zipUrl);
            encoding.toCharset().ifPresent(cs -> params.put(ShapefileDataStoreFactory.DBFCHARSET.key, cs.name()));
            final DataStore dataStore = new ShapefileDataStoreFactory().createDataStore(params);
            try {
                final SimpleFeatureSource source =
                    dataStore.getFeatureSource(dataStore.getTypeNames()[0]);
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

    private static BufferedDataTable readKmz(final FSPath path, final ExecutionContext exec) throws Exception {
        final LocalFileHandle local = LocalFileStaging.resolveExistingToLocalFile(path);
        try {
            byte[] kmlBytes = null;
            try (ZipFile zip = new ZipFile(local.path())) {
                for (final var entries = zip.entries(); entries.hasMoreElements();) {
                    final ZipEntry entry = entries.nextElement();
                    if (entry.getName().toLowerCase().endsWith(".kml")) {
                        if (kmlBytes != null) {
                            throw new KNIMEException("Node supports only kmz files with a single kml file");
                        }
                        try (InputStream in = zip.getInputStream(entry)) {
                            kmlBytes = in.readAllBytes();
                        }
                    }
                }
            }
            if (kmlBytes == null) {
                throw new KNIMEException("The kmz file contains no kml file.");
            }
            try (InputStream in = new ByteArrayInputStream(kmlBytes)) {
                return KmlReader.read(in, exec);
            }
        } finally {
            local.close();
        }
    }

    /** A {@link ParquetReader.Builder} for {@link Group} rows that never constructs a Hadoop {@code Configuration}. */
    private static final class GroupReaderBuilder extends ParquetReader.Builder<Group> {
        GroupReaderBuilder(final InputFile file) {
            // ParquetReader.Builder's other constructors eagerly build a default Hadoop Configuration (which then
            // tries to parse Hadoop's XML config resources) purely as a field initializer; passing a
            // PlainParquetConfiguration upfront is the only constructor that skips that.
            super(file, new PlainParquetConfiguration());
        }

        @Override
        protected ReadSupport<Group> getReadSupport() {
            return new GroupReadSupport();
        }
    }

    private static BufferedDataTable readGeoParquet(final FSPath path, final ExecutionContext exec) throws Exception {
        final LocalFileHandle local = LocalFileStaging.resolveExistingToLocalFile(path);
        try {
            final InputFile inputFile = new LocalInputFile(java.nio.file.Path.of(local.path()));
            final ParquetReadOptions readOptions = ParquetReadOptions.builder(new PlainParquetConfiguration())
                .withCodecFactory(HadoopFreeParquetCodecFactory.INSTANCE).build();

            final MessageType schema;
            final String geoMetaJson;
            try (ParquetFileReader fileReader = ParquetFileReader.open(inputFile, readOptions)) {
                schema = fileReader.getFileMetaData().getSchema();
                geoMetaJson = fileReader.getFileMetaData().getKeyValueMetaData().get("geo");
            }

            final String geometryColumn = extractPrimaryGeometryColumn(geoMetaJson, schema);
            final GeoReferenceSystem refSystem = extractCrs(geoMetaJson, geometryColumn);

            final List<Type> fields = schema.getFields();
            final DataColumnSpec[] colSpecs = new DataColumnSpec[fields.size()];
            for (int i = 0; i < fields.size(); i++) {
                final Type field = fields.get(i);
                final DataType type = field.getName().equals(geometryColumn) ? GeoCell.TYPE
                    : parquetTypeToDataType(field.asPrimitiveType());
                colSpecs[i] = new DataColumnSpecCreator(field.getName(), type).createSpec();
            }
            final DataTableSpec spec = new DataTableSpec(colSpecs);

            final BufferedDataContainer container = exec.createDataContainer(spec, false);
            long rowIdx = 0;
            try (ParquetReader<Group> reader =
                new GroupReaderBuilder(inputFile).withCodecFactory(HadoopFreeParquetCodecFactory.INSTANCE).build()) {
                Group group;
                while ((group = reader.read()) != null) {
                    exec.checkCanceled();
                    final DataCell[] cells = new DataCell[fields.size()];
                    for (int i = 0; i < fields.size(); i++) {
                        final Type field = fields.get(i);
                        if (group.getFieldRepetitionCount(i) == 0) {
                            cells[i] = DataType.getMissingCell();
                        } else if (field.getName().equals(geometryColumn)) {
                            cells[i] = GeoCellFactory.create(group.getBinary(i, 0).getBytes(), refSystem);
                        } else {
                            cells[i] = groupValueToDataCell(group, i, field.asPrimitiveType());
                        }
                    }
                    container.addRowToTable(new DefaultRow(RowKey.createRowKey(rowIdx++), cells));
                }
            }
            container.close();
            return container.getTable();
        } finally {
            local.close();
        }
    }

    /**
     * Determines the geometry column per the GeoParquet spec's {@code "geo"} file metadata key
     * ({@code primary_column}), falling back to a plain column named {@code geometry} if the file carries no such
     * metadata at all.
     */
    private static String extractPrimaryGeometryColumn(final String geoMetaJson, final MessageType schema)
        throws KNIMEException {
        if (geoMetaJson != null) {
            try {
                final JsonNode primary = new ObjectMapper().readTree(geoMetaJson).get("primary_column");
                if (primary != null && !primary.isNull()) {
                    return primary.asText();
                }
            } catch (final IOException e) {
                throw KNIMEException
                    .of(Message.fromSummary("Could not parse the parquet file's 'geo' metadata: " + e.getMessage()), e);
            }
        }
        return schema.getFields().stream().map(Type::getName).filter(n -> n.equalsIgnoreCase("geometry")).findFirst()
            .orElseThrow(() -> new KNIMEException("The parquet file has no 'geo' metadata and no 'geometry' column; "
                + "cannot determine which column holds the geometry."));
    }

    /**
     * Resolves the geometry column's CRS per the GeoParquet spec: a {@code null}/absent {@code "crs"} entry means
     * the default CRS (OGC:CRS84, i.e. WGS84). A full PROJJSON CRS definition is not parsed here — only the common
     * case of a PROJJSON object carrying a top-level {@code "id": {"authority": ..., "code": ...}} identifier is
     * recognized; anything else falls back to the spec default rather than guessing.
     */
    private static GeoReferenceSystem extractCrs(final String geoMetaJson, final String geometryColumn) {
        if (geoMetaJson != null) {
            try {
                final JsonNode root = new ObjectMapper().readTree(geoMetaJson);
                final JsonNode columns = root.get("columns");
                final JsonNode columnMeta = columns == null ? null : columns.get(geometryColumn);
                final JsonNode crs = columnMeta == null ? null : columnMeta.get("crs");
                final JsonNode id = crs == null ? null : crs.get("id");
                if (id != null && id.has("authority") && id.has("code")) {
                    return GeoReferenceSystemFactory.create(id.get("authority").asText() + ":" + id.get("code").asText());
                }
            } catch (final IOException e) { // NOSONAR - falling back to the spec default is correct on any parse failure
                // ignore, fall back below
            }
        }
        return GeoReferenceSystem.DEFAULT;
    }

    private static DataType parquetTypeToDataType(final PrimitiveType type) throws KNIMEException {
        return switch (type.getPrimitiveTypeName()) {
            case INT32 -> IntCell.TYPE;
            case INT64 -> LongCell.TYPE;
            case DOUBLE, FLOAT -> DoubleCell.TYPE;
            case BOOLEAN -> BooleanCell.TYPE;
            case BINARY -> StringCell.TYPE;
            default -> throw new KNIMEException("Unsupported parquet column type '" + type.getPrimitiveTypeName()
                + "' for column '" + type.getName() + "'.");
        };
    }

    private static DataCell groupValueToDataCell(final Group group, final int fieldIndex, final PrimitiveType type) {
        return switch (type.getPrimitiveTypeName()) {
            case INT32 -> new IntCell(group.getInteger(fieldIndex, 0));
            case INT64 -> new LongCell(group.getLong(fieldIndex, 0));
            case DOUBLE -> new DoubleCell(group.getDouble(fieldIndex, 0));
            case FLOAT -> new DoubleCell(group.getFloat(fieldIndex, 0));
            case BOOLEAN -> BooleanCell.get(group.getBoolean(fieldIndex, 0));
            case BINARY -> new StringCell(group.getString(fieldIndex, 0));
            default ->
                throw new IllegalStateException("Unsupported parquet column type: " + type.getPrimitiveTypeName());
        };
    }
}

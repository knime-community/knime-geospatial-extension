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
package org.knime.geospatial.io.geopackagewriter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.geotools.api.data.SimpleFeatureWriter;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.api.filter.Filter;
import org.geotools.api.data.Transaction;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.geopkg.FeatureEntry;
import org.geotools.geopkg.GeoPackage;
import org.knime.core.data.DataCell;
import org.knime.core.data.DataRow;
import org.knime.core.data.DataTableSpec;
import org.knime.core.data.def.BooleanCell;
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
        .name("GeoPackage Writer (Java)") //
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

        // A blank layer name reaches GeoPackage.create()/GeoTools as a literal empty-string SQL table name. SQLite
        // itself accepts a quoted "" identifier, but the JDBC driver's DatabaseMetaData.getPrimaryKeys() then throws
        // "Invalid table name: ''" on it, so GeoTools can never detect a primary key for that table and permanently
        // treats it as a read-only FeatureSource - every future write attempt against that same (now-poisoned)
        // layer name fails with the misleading "IOException: is read only", even though the file itself is
        // perfectly writable. Reject this up front instead of silently creating an unusable layer.
        if (parameters.m_layer == null || parameters.m_layer.isBlank()) {
            throw new KNIMEException("Output layer name must not be empty.");
        }

        final Consumer<StatusMessage> statusConsumer = msg -> {
            if (msg.getType() == StatusMessage.MessageType.WARNING || msg.getType() == StatusMessage.MessageType.ERROR) {
                out.setWarningMessage(msg.getMessage());
            }
        };

        try (final var accessor = parameters.m_outputFile.getPathAccessor(FILE_SELECTION_CONFIG, Optional.empty())) {
            final FSPath rawDestPath = accessor.getOutputPath(statusConsumer);
            final FSPath destPath = (FSPath)rawDestPath
                .resolveSibling(GeoFileNames.ensureExtension(rawDestPath.getFileName().toString(), ".gpkg"));

            final boolean destExists = Files.exists(destPath);
            if (destExists && parameters.m_overwritePolicy == ExistingFile.FAIL) {
                throw new KNIMEException(
                    "Output file \"" + destPath + "\" already exists - must not overwrite as per user setting");
            }

            // Always stage into a fresh, guaranteed-writable local temp file - never write in place into whatever
            // resolveExistingToLocalFile() hands back. That method is a read-oriented utility (its every other use
            // in this codebase is a Reader resolving a file to read from); this codebase's convention is to only
            // ever write to a local staging file and upload it afterwards. Seeding the fresh staging file with the
            // existing destination's bytes (when overwriting a layer into an already-existing file) preserves the
            // existing layers for the create-vs-overwrite logic in writeLayer() below, exactly as if it had been
            // opened in place.
            final Path localFile = LocalFileStaging.createLocalStagingFile("geopackagewriter-", ".gpkg");
            if (destExists) {
                try (LocalFileHandle existingLocal = LocalFileStaging.resolveExistingToLocalFile(destPath)) {
                    Files.copy(Path.of(existingLocal.path()), localFile, StandardCopyOption.REPLACE_EXISTING);
                }
            }

            try {
                writeLayer(localFile, table, geoColIdx, parameters.m_layer, exec);
                final var openOptions =
                    new java.nio.file.OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING};
                LocalFileStaging.uploadFileTo(localFile, destPath, openOptions);
            } finally {
                LocalFileStaging.deleteQuietly(localFile);
            }
        } catch (final CanceledExecutionException | KNIMEException e) {
            throw e;
        } catch (final Exception e) {
            throw KNIMEException.of(Message.fromSummary("Error writing file: " + e.getMessage()), e);
        }
    }

    private static void writeLayer(final Path localFile, final BufferedDataTable table, final int geoColIdx,
        final String layerName, final ExecutionContext exec) throws IOException, CanceledExecutionException,
    	IndexOutOfBoundsException, KNIMEException, SQLException {
        // Overwriting an existing layer must fully replace its schema, not just its rows - matching GeoPandas' own
        // to_file(..., layer=...) semantics (documented in the Python node's own docstring as "the layer will be
        // overwritten without a warning"). Reusing the existing on-disk table's own schema instead (by opening a
        // writer against its pre-existing FeatureEntry) made the attribute writes below go by GeoTools feature-
        // attribute *position*, which silently mismatched whenever the existing table's column count/order differed
        // from the new data's (e.g. an extra "id" column left over from a prior Python write) - so drop it first,
        // via a completely separate GeoPackage instance/connection that is discarded afterwards, and only then open
        // a fresh one to (re)create the entry from this write's own featureType. Doing the drop+recreate through the
        // same still-open GeoPackage object risked its ContentDataStore serving stale cached schema/type-name state
        // for the entry it had just seen deleted out from under it via raw SQL.
        try (var probe = new GeoPackage(new File(localFile.toString()))) {
            // Required even for an existing file - a brand-new local staging file has no gpkg_contents/
            // gpkg_geometry_columns schema yet, so probe.features() below throws "no such table: gpkg_contents"
            // without this; safe/idempotent to call on an already-initialized file too.
            probe.init();
            if (probe.features().stream().anyMatch(candidate -> candidate.getTableName().equals(layerName))) {
                dropExistingLayer(localFile, layerName);
            }
        }

        try (var geoPackage = new GeoPackage(new File(localFile.toString()))) {
            geoPackage.init();
            final DataTableSpec spec = table.getDataTableSpec();
            final var crs = GeoTypeMapping.findCrs(table, geoColIdx);

            final var builder = new SimpleFeatureTypeBuilder();
            builder.setName(layerName);
            for (int i = 0; i < spec.getNumColumns(); i++) {
                final String name = spec.getColumnSpec(i).getName();
                if (i == geoColIdx) {
                    builder.add(name, Geometry.class, crs);
                } else {
                    builder.add(name, GeoTypeMapping.javaTypeForGeoPackage(spec.getColumnSpec(i).getType()));
                }
            }
            final SimpleFeatureType featureType = builder.buildFeatureType();

            final FeatureEntry entry = new FeatureEntry();
            entry.setTableName(layerName);
            // GeoPackage.create() requires bounds to be set up front - it throws IllegalArgumentException
            // ("Entry must have bounds") otherwise, on every very first write to a new layer.
            entry.setBounds(computeBounds(table, geoColIdx, crs));
            geoPackage.create(entry, featureType);
            fixBooleanColumnTypes(localFile, layerName, booleanColumnNames(spec, geoColIdx));

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
                    // GeoTools' insert-mode SimpleFeatureWriter requires hasNext() polled before each next() to
                    // advance/allocate a feature slot; skipping it throws IllegalStateException on the first row.
                    writer.hasNext();
                    final var feature = writer.next();
                    for (int i = 0; i < row.getNumCells(); i++) {
                        final var cell = row.getCell(i);
                        if (i == geoColIdx) {
                            feature.setAttribute(i, cell.isMissing() ? null : GeoTypeMapping.toJtsGeometry((GeoValue)cell));
                        } else {
                            feature.setAttribute(i, GeoTypeMapping.toJavaValueForGeoPackage(cell));
                        }
                    }
                    writer.write();
                    rowIdx++;
                }
            }
        }
    }

    private static List<String> booleanColumnNames(final DataTableSpec spec, final int geoColIdx) {
        final List<String> names = new ArrayList<>();
        for (int i = 0; i < spec.getNumColumns(); i++) {
            if (i != geoColIdx && spec.getColumnSpec(i).getType().equals(BooleanCell.TYPE)) {
                names.add(spec.getColumnSpec(i).getName());
            }
        }
        return names;
    }

    /**
     * Fully removes an existing layer - its data table, spatial index shadow tables, and every {@code gpkg_*}
     * bookkeeping row referencing it - so it can be recreated from scratch with a new schema.
     * {@link GeoPackage#deleteGeoPackageContentsEntry}/{@code deleteGeometryColumnsEntry} exist for exactly this but
     * are package-private to {@code org.geotools.geopkg}, so this replicates them via raw SQL instead.
     */
    private static void dropExistingLayer(final Path localFile, final String layerName) throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + localFile);
            Statement stmt = conn.createStatement()) {
            final List<String> rtreeTables = new ArrayList<>();
            try (PreparedStatement select =
                conn.prepareStatement("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE ? ESCAPE '\\'")) {
                select.setString(1, "rtree\\_" + escapeLike(layerName) + "\\_%");
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        rtreeTables.add(rs.getString(1));
                    }
                }
            }
            for (final String rtreeTable : rtreeTables) {
                stmt.execute("DROP TABLE IF EXISTS " + quoteIdentifier(rtreeTable));
            }
            stmt.execute("DROP TABLE IF EXISTS " + quoteIdentifier(layerName));
            for (final String bookkeepingTable : List.of("gpkg_extensions", "gpkg_geometry_columns", "gpkg_contents",
                "gpkg_ogr_contents")) {
                try (PreparedStatement checkExists =
                    conn.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
                    checkExists.setString(1, bookkeepingTable);
                    try (ResultSet rs = checkExists.executeQuery()) {
                        if (!rs.next()) {
                            continue;
                        }
                    }
                }
                try (PreparedStatement delete =
                    conn.prepareStatement("DELETE FROM " + bookkeepingTable + " WHERE table_name = ?")) {
                    delete.setString(1, layerName);
                    delete.executeUpdate();
                }
            }
        }
    }

    private static String escapeLike(final String value) {
        return value.replace("\\", "\\\\").replace("_", "\\_").replace("%", "\\%");
    }

    private static String quoteIdentifier(final String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    /**
     * Works around a GeoTools/GeoPackage bug: {@link GeoPackage#create(FeatureEntry, SimpleFeatureType)} declares a
     * {@code Boolean.class}-bound column as SQLite type {@code MEDIUMINT} rather than the GeoPackage spec's own
     * {@code BOOLEAN} type - verified empirically that GeoTools' own GeoPackage reader correctly maps a column back
     * to {@code Boolean} only when its declared SQL type is literally {@code BOOLEAN}; with {@code MEDIUMINT} it
     * reads back as plain {@code Integer} (0/1), losing the column's boolean-ness on every read. Patches the just-
     * created (still-empty) table's stored {@code CREATE TABLE} text via SQLite's {@code writable_schema} pragma -
     * the table's actual storage is untouched (both type names have integer-compatible affinity), only the
     * annotation text changes, which is all a later, independent read needs to map the column back to
     * {@code Boolean} correctly. Verified this does not disturb the write that follows in the same still-open
     * {@link GeoPackage} connection.
     */
    private static void fixBooleanColumnTypes(final Path localFile, final String layerName,
        final List<String> booleanColumns) throws SQLException {
        if (booleanColumns.isEmpty()) {
            return;
        }
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + localFile)) {
            try (Statement pragmaOn = conn.createStatement()) {
                pragmaOn.execute("PRAGMA writable_schema=ON");
            }
            String ddl;
            try (PreparedStatement select =
                conn.prepareStatement("SELECT sql FROM sqlite_master WHERE type='table' AND name=?")) {
                select.setString(1, layerName);
                try (ResultSet rs = select.executeQuery()) {
                    if (!rs.next()) {
                        return;
                    }
                    ddl = rs.getString(1);
                }
            }
            for (final String column : booleanColumns) {
                ddl = ddl.replaceAll("(?i)(\"" + Pattern.quote(column) + "\")\\s+\\w+", "$1 BOOLEAN");
            }
            try (PreparedStatement update =
                conn.prepareStatement("UPDATE sqlite_master SET sql=? WHERE type='table' AND name=?")) {
                update.setString(1, ddl);
                update.setString(2, layerName);
                update.executeUpdate();
            }
            try (Statement pragmaOff = conn.createStatement()) {
                pragmaOff.execute("PRAGMA writable_schema=OFF");
            }
        }
    }

    /**
     * Computes the layer's spatial extent up front by unioning every non-missing geometry's envelope - needed
     * because {@link GeoPackage#create(FeatureEntry, SimpleFeatureType)} requires bounds before any feature is
     * written, so a full {@link ListFeatureCollection} can't be relied on to supply them afterwards. Falls back to
     * an empty (but CRS-tagged) envelope if the column has no non-missing values to derive bounds from.
     */
    private static org.geotools.geometry.jts.ReferencedEnvelope computeBounds(final BufferedDataTable table,
        final int geoColIdx, final org.geotools.api.referencing.crs.CoordinateReferenceSystem crs)
        throws KNIMEException {
        final var envelope = new org.geotools.geometry.jts.ReferencedEnvelope(crs);
        for (final DataRow row : table) {
            final var cell = row.getCell(geoColIdx);
            if (!cell.isMissing()) {
                envelope.expandToInclude(GeoTypeMapping.toJtsGeometry((GeoValue)cell).getEnvelopeInternal());
            }
        }
        return envelope;
    }
}

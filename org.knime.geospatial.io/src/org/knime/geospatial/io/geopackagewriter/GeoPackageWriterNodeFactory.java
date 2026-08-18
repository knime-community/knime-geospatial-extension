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
        final String layerName, final ExecutionContext exec) throws IOException, CanceledExecutionException,
    	IndexOutOfBoundsException, KNIMEException, SQLException {
        try (var geoPackage = new GeoPackage(new File(localFile.toString()))) {
            // Required even for an existing file - a brand-new local staging file has no gpkg_contents/
            // gpkg_geometry_columns schema yet, so geoPackage.features() below throws "no such table:
            // gpkg_contents" without this; safe/idempotent to call on an already-initialized file too.
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
                // GeoPackage.create() requires bounds to be set up front - it throws IllegalArgumentException
                // ("Entry must have bounds") otherwise, on every very first write to a new layer.
                entry.setBounds(computeBounds(table, geoColIdx, crs));
                geoPackage.create(entry, featureType);
                fixBooleanColumnTypes(localFile, layerName, booleanColumnNames(spec, geoColIdx));
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

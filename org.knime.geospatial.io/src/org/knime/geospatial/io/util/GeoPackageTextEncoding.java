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
 *   18 Aug 2026 (AI): created
 */
package org.knime.geospatial.io.util;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Makes the {@code Encoding} parameter genuinely take effect for GeoPackage TEXT columns, by bypassing GeoTools'/the
 * SQLite JDBC driver's built-in text handling (which always encodes/decodes as UTF-8, and offers no charset
 * parameter at all - confirmed via API inspection of {@code GeoPkgDataStoreFactory}) and instead reading/writing the
 * column's raw bytes directly, decoding/encoding them with the caller-supplied {@link Charset}.
 * <p>
 * <b>This is a deliberate, requested departure from the GeoPackage specification</b>, which mandates UTF-8 for all
 * text - a GeoPackage written with a non-UTF-8 encoding here is no longer spec-compliant and will show garbled text
 * (or fail outright) in any other compliant reader, including GDAL/QGIS/ArcGIS. It is safe and self-consistent only
 * within this bundle's own Reader/Writer pair, which both bypass the same UTF-8 assumption the same way. This is
 * exactly the failure mode already confirmed (and left unfixed, by explicit instruction) in the Python
 * extension's {@code GeoPackageWriterNode}, which attempts something similar but only on the write side, and with
 * codec name strings ({@code "WINDOWS1252"}) that Python's own {@code codecs} module doesn't even recognize for
 * several of its options - reproduced here deliberately, correctly, and symmetrically on both read and write.
 * <p>
 * Verified empirically that SQLite/the xerial JDBC driver store a value bound via {@link PreparedStatement#setBytes}
 * with storage class {@code BLOB} (bypassing any TEXT-affinity coercion or UTF-8 re-encoding) even in a
 * column declared {@code TEXT}, and that {@link ResultSet#getBytes} retrieves those bytes back byte-for-byte
 * unchanged - the round trip through raw bytes is lossless; only GeoTools'/the driver's own {@code getString()} (an
 * unconditional UTF-8 decode) would garble it, which is exactly what this class avoids calling.
 */
public final class GeoPackageTextEncoding {

    private GeoPackageTextEncoding() {
        // utility class
    }

    /**
     * Writer-side half: opened once per write operation, then {@link #patch} called once per (row, text column)
     * pair immediately after each row is written via the normal GeoTools {@code SimpleFeatureWriter}, to overwrite
     * that cell with the row's {@code fid}-addressed raw bytes in the target charset - GeoTools has already written
     * a UTF-8 encoding of whatever Java value it was given for that cell, which this then replaces outright.
     */
    public static final class Writer implements AutoCloseable {

        private final Connection m_connection;
        private final String m_quotedLayerName;
        private final Charset m_charset;
        private final Map<String, PreparedStatement> m_statements = new HashMap<>();

        public Writer(final Path localFile, final String layerName, final Charset charset) throws SQLException {
            m_connection = DriverManager.getConnection("jdbc:sqlite:" + localFile);
            m_quotedLayerName = quoteIdentifier(layerName);
            m_charset = charset;
        }

        /**
         * Overwrites the given row's ({@code fid}, addressed via GeoTools' own {@code feature.getID()}) column with
         * {@code value} encoded as raw bytes in this writer's target charset.
         *
         * @throws CharacterCodingException if {@code value} contains a character the target charset cannot
         *             represent - a real, silent-data-loss gap that would otherwise pass unnoticed: unlike
         *             {@link String#getBytes(Charset)} (used naively, this would substitute {@code '?'} for every
         *             unrepresentable character with no indication anything was lost - confirmed empirically, e.g.
         *             encoding "München" as ASCII silently yields "M?nchen"), this uses a strict
         *             {@link CharsetEncoder} that reports the failure instead, so the caller can surface it as a
         *             clear error rather than writing corrupted text. Python's equivalent write path either crashes
         *             outright on a bad codec name or writes silently-corrupted/unreadable text depending on the
         *             chosen encoding - this is deliberately stricter than either.
         */
        public void patch(final long fid, final String columnName, final String value)
            throws SQLException, CharacterCodingException {
            final CharsetEncoder encoder = m_charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
            final ByteBuffer encodedBuffer = encoder.encode(CharBuffer.wrap(value));
            final byte[] encoded = new byte[encodedBuffer.remaining()];
            encodedBuffer.get(encoded);

            PreparedStatement statement = m_statements.get(columnName);
            if (statement == null) {
                statement = m_connection
                    .prepareStatement("UPDATE " + m_quotedLayerName + " SET " + quoteIdentifier(columnName)
                        + " = ? WHERE fid = ?");
                m_statements.put(columnName, statement);
            }
            statement.setBytes(1, encoded);
            statement.setLong(2, fid);
            statement.executeUpdate();
        }

        /** Parses the numeric {@code fid} out of a GeoTools GeoPackage feature ID, e.g. {@code "layer1.42"} -> 42. */
        public static long parseFid(final String featureId) {
            final int dot = featureId.lastIndexOf('.');
            return Long.parseLong(dot < 0 ? featureId : featureId.substring(dot + 1));
        }

        @Override
        public void close() throws SQLException {
            for (final PreparedStatement statement : m_statements.values()) {
                statement.close();
            }
            m_connection.close();
        }
    }

    /**
     * Reader-side half: bulk-reads every row's raw bytes for the given text columns in one pass, decoding them with
     * {@code charset} instead of the driver's own UTF-8-only {@code getString()} - keyed by {@code fid} so callers
     * can look up the correctly-decoded value for each column while otherwise iterating features normally via
     * GeoTools (whose {@code SimpleFeature.getID()} carries the same {@code fid}, per
     * {@link Writer#parseFid(String)}).
     *
     * @throws CharacterCodingException if a column's raw bytes are not valid {@code charset} text - i.e. the
     *             selected Encoding doesn't actually match how the file was written. Uses a strict
     *             {@link java.nio.charset.CharsetDecoder} that reports this instead of {@code new String(bytes,
     *             charset)}'s default of silently substituting the Unicode replacement character
     *             ({@code U+FFFD}) for every invalid byte sequence - which would otherwise look like a successful
     *             read of subtly-wrong data rather than the clear, actionable "wrong encoding selected" error this
     *             produces instead.
     */
    public static Map<Long, Map<String, String>> readRaw(final Path localFile, final String layerName,
        final List<String> columnNames, final Charset charset) throws SQLException, CharacterCodingException {
        final Map<Long, Map<String, String>> result = new HashMap<>();
        if (columnNames.isEmpty()) {
            return result;
        }
        final String columnList = String.join(", ", columnNames.stream().map(GeoPackageTextEncoding::quoteIdentifier)
            .toArray(String[]::new));
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + localFile);
            PreparedStatement statement =
                connection.prepareStatement("SELECT fid, " + columnList + " FROM " + quoteIdentifier(layerName));
            ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                final long fid = rs.getLong(1);
                final Map<String, String> row = new HashMap<>();
                for (int i = 0; i < columnNames.size(); i++) {
                    final byte[] raw = rs.getBytes(i + 2);
                    row.put(columnNames.get(i), raw == null ? null : decodeStrict(raw, charset));
                }
                result.put(fid, row);
            }
        }
        return result;
    }

    private static String decodeStrict(final byte[] bytes, final Charset charset) throws CharacterCodingException {
        final var decoder = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static String quoteIdentifier(final String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }
}

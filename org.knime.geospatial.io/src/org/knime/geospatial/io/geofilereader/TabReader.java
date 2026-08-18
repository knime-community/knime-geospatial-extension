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
package org.knime.geospatial.io.geofilereader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.knime.core.data.DataCell;
import org.knime.core.data.DataColumnSpec;
import org.knime.core.data.DataColumnSpecCreator;
import org.knime.core.data.DataTableSpec;
import org.knime.core.data.DataType;
import org.knime.core.data.RowKey;
import org.knime.core.data.def.BooleanCell;
import org.knime.core.data.def.DefaultRow;
import org.knime.core.data.def.DoubleCell;
import org.knime.core.data.def.IntCell;
import org.knime.core.data.def.LongCell;
import org.knime.core.data.def.StringCell;
import org.knime.core.data.time.localdate.LocalDateCellFactory;
import org.knime.core.data.time.localdatetime.LocalDateTimeCellFactory;
import org.knime.core.data.time.localtime.LocalTimeCellFactory;
import org.knime.core.node.BufferedDataContainer;
import org.knime.core.node.BufferedDataTable;
import org.knime.core.node.ExecutionContext;
import org.knime.core.node.KNIMEException;
import org.knime.core.node.message.Message;
import org.knime.filehandling.core.connections.FSFiles.LocalFileHandle;
import org.knime.filehandling.core.connections.FSPath;
import org.knime.geospatial.core.data.cell.GeoCell;
import org.knime.geospatial.core.data.cell.GeoCellFactory;
import org.knime.geospatial.core.data.reference.GeoReferenceSystem;
import org.knime.geospatial.io.util.GeoFileEncoding;
import org.knime.geospatial.io.util.LocalFileStaging;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.WKBWriter;

/**
 * Hand-rolled MapInfo TAB reader (companion {@code .dat}/{@code .map}/{@code .id} files) - no pure-Java library
 * exists anywhere for this (confirmed absent from GeoTools and Apache SIS), and the only alternative,
 * {@code gt-ogr-jni}, requires a native GDAL install and isn't published to Maven Central, which would break this
 * bundle's no-native-dependency vendoring model. GeoTools' own {@link org.geotools.data.MapInfoFileReader} only
 * parses the {@code .tab} header's georeferencing/control-point info (for raster world-file-style use), not feature
 * geometry or attributes at all - it is not reused here since it doesn't help with any of that.
 * <p>
 * Every byte-level rule below - the {@code .tab} header grammar, the {@code .dat} file's dBASE-III-like structure
 * with MapInfo's binary (not ASCII) encodings for its numeric/date/time/logical field types, the {@code .map}
 * header's coordinate scale/displacement/quadrant fields, the {@code .id} file's flat offset array, and the
 * per-object-type binary layouts - was cross-verified two ways: (1) against GDAL's own open-source "mitab" driver
 * source (the reference implementation for this proprietary-but-reverse-engineered format), and (2) empirically, by
 * generating real {@code .tab}/{@code .dat}/{@code .map}/{@code .id} files with GDAL/geopandas and hex-dumping them
 * against known input values (coordinates, attribute values of every supported type, dates/times, nulls) to confirm
 * every offset and formula actually produces the expected result, not just what the source code seems to say.
 * <p>
 * Deliberately out of scope: geometry types other than Point/Line/Polyline/Region (Text, Arc, Ellipse,
 * Rectangle/RoundedRectangle, Collection, FontSymbol/CustomSymbol, and the {@code gx:}-style Model/Track equivalents
 * this format doesn't actually have but similarly-exotic MapInfo symbol/style objects do) - these throw a clear
 * error rather than silently producing wrong geometry. MultiPoint is included in that list too: empirically, even
 * GDAL's own MapInfo writer doesn't preserve a multi-point feature as one row - it explodes it into one row per
 * point (verified by round-tripping a 3-point MultiPoint through GDAL and observing 3 separate rows/objects come
 * out) - so there is no real interoperability need to decode a native multi-point object type here. Compressed
 * (int16-delta) coordinate encoding is supported for Point/Line/Polyline, matching the reference source's documented
 * formulas, but NOT for multi-section Region/Multiline objects - the reference source itself leaves the exact
 * compressed section-header layout ambiguous in that case (whether the per-section MBR shrinks to int16 too is not
 * confirmed by anything short of a real compressed sample, which GDAL's writer never produces), so this fails
 * clearly there rather than risk silently misreading vertex data.
 */
final class TabReader {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    private static final int HEADER_BLOCK_SIZE = 1024;
    private static final int OBJECT_BLOCK_HEADER_SIZE = 20;
    private static final int COORD_BLOCK_HEADER_SIZE = 8;

    private static final int TYPE_NONE = 0x00;
    private static final int TYPE_SYMBOL_C = 0x01;
    private static final int TYPE_SYMBOL = 0x02;
    private static final int TYPE_LINE_C = 0x04;
    private static final int TYPE_LINE = 0x05;
    private static final int TYPE_PLINE_C = 0x07;
    private static final int TYPE_PLINE = 0x08;
    private static final int TYPE_REGION_C = 0x0d;
    private static final int TYPE_REGION = 0x0e;
    private static final int TYPE_MULTIPLINE_C = 0x25;
    private static final int TYPE_MULTIPLINE = 0x26;
    private static final int TYPE_V450_REGION_C = 0x2e;
    private static final int TYPE_V450_REGION = 0x2f;
    private static final int TYPE_V450_MULTIPLINE_C = 0x31;
    private static final int TYPE_V450_MULTIPLINE = 0x32;
    private static final int TYPE_V800_REGION_C = 0x3d;
    private static final int TYPE_V800_REGION = 0x3e;
    private static final int TYPE_V800_MULTIPLINE_C = 0x40;
    private static final int TYPE_V800_MULTIPLINE = 0x41;

    private TabReader() {
        // utility class
    }

    static BufferedDataTable read(final FSPath tabPath, final ExecutionContext exec, final GeoFileEncoding encoding)
        throws Exception {
        final LocalFileHandle localTab = LocalFileStaging.resolveExistingToLocalFile(tabPath);
        try {
            final Path tabFile = Path.of(localTab.path());
            final String baseName = stripExtension(tabFile.getFileName().toString());
            final Path dir = tabFile.getParent();

            final TabHeader header = parseTabHeader(tabFile);
            final Charset charset = encoding.toCharset().orElse(header.charset());

            final byte[] dat = Files.readAllBytes(findCompanionFile(dir, baseName, "dat"));
            final byte[] map = Files.readAllBytes(findCompanionFile(dir, baseName, "map"));
            final byte[] id = Files.readAllBytes(findCompanionFile(dir, baseName, "id"));

            final MapHeader mapHeader = parseMapHeader(map);
            final List<Object[]> attributeRows = readDatRecords(dat, header.fields(), charset);

            final DataColumnSpec[] specs = new DataColumnSpec[header.fields().size() + 1];
            for (int i = 0; i < header.fields().size(); i++) {
                final TabField field = header.fields().get(i);
                specs[i] = new DataColumnSpecCreator(field.name(), knimeTypeFor(field.type())).createSpec();
            }
            specs[header.fields().size()] = new DataColumnSpecCreator("geometry", GeoCell.TYPE).createSpec();
            final DataTableSpec spec = new DataTableSpec(specs);

            final BufferedDataContainer container = exec.createDataContainer(spec, false);
            final long rowCount = attributeRows.size();
            for (int r = 0; r < attributeRows.size(); r++) {
                exec.checkCanceled();
                if (rowCount > 0) {
                    exec.setProgress((double)r / rowCount, "Reading row " + r + "/" + rowCount);
                }
                final long idOffset = readIdOffset(id, r);
                final Geometry geometry = idOffset == 0 ? null : readGeometryAt(map, (int)idOffset, mapHeader);
                final Object[] row = attributeRows.get(r);
                final DataCell[] cells = new DataCell[specs.length];
                for (int i = 0; i < row.length; i++) {
                    cells[i] = toDataCell(row[i], header.fields().get(i).type());
                }
                cells[specs.length - 1] = toGeoCell(geometry);
                container.addRowToTable(new DefaultRow(RowKey.createRowKey(r), cells));
            }
            container.close();
            return container.getTable();
        } finally {
            localTab.close();
        }
    }

    private static String stripExtension(final String fileName) {
        final int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }

    /** MapInfo TAB's companion files always sit next to the {@code .tab} file with the same base name. */
    private static Path findCompanionFile(final Path dir, final String baseName, final String extension)
        throws KNIMEException {
        for (final String ext : new String[]{extension, extension.toUpperCase(Locale.ROOT)}) {
            final Path candidate = dir.resolve(baseName + "." + ext);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new KNIMEException(
            "Missing companion file \"" + baseName + "." + extension + "\" next to the .tab file.");
    }

    // ------------------------------------------------------------------------------------------------------------
    // .tab header (field definitions + charset)
    // ------------------------------------------------------------------------------------------------------------

    enum TabFieldType {
            CHAR, SMALLINT, INTEGER, LARGEINT, DECIMAL, FLOAT, DATE, TIME, DATETIME, LOGICAL
    }

    record TabField(String name, TabFieldType type, int width, int decimals) {
    }

    record TabHeader(List<TabField> fields, Charset charset) {
    }

    private static final Pattern FIELDS_COUNT_LINE = Pattern.compile("(?i)^Fields\\s+(\\d+)\\s*$");

    private static final Pattern FIELD_LINE =
        Pattern.compile("(?i)^(\\S+)\\s+(\\w+)\\s*(?:\\(([^)]*)\\))?\\s*;?\\s*$");

    private static TabHeader parseTabHeader(final Path tabFile) throws IOException, KNIMEException {
        final List<String> lines = Files.readAllLines(tabFile, StandardCharsets.ISO_8859_1);
        String charsetName = "Neutral";
        final List<TabField> fields = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            final String line = lines.get(i).trim();
            if (line.regionMatches(true, 0, "!charset", 0, 8)) {
                charsetName = line.substring(8).trim();
                continue;
            }
            final Matcher fieldsMatcher = FIELDS_COUNT_LINE.matcher(line);
            if (fieldsMatcher.matches()) {
                final int n = Integer.parseInt(fieldsMatcher.group(1));
                for (int f = 0; f < n && ++i < lines.size(); f++) {
                    fields.add(parseFieldLine(lines.get(i)));
                }
            }
        }
        if (fields.isEmpty()) {
            throw new KNIMEException(
                "Could not find a \"Fields\" definition in the .tab file \"" + tabFile.getFileName() + "\".");
        }
        return new TabHeader(fields, mapMapInfoCharset(charsetName));
    }

    private static TabField parseFieldLine(final String line) throws KNIMEException {
        final Matcher m = FIELD_LINE.matcher(line.trim());
        if (!m.matches()) {
            throw new KNIMEException("Could not parse .tab field definition: \"" + line + "\".");
        }
        final String name = m.group(1);
        final TabFieldType type;
        try {
            type = TabFieldType.valueOf(m.group(2).toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            throw new KNIMEException(
                "Unsupported .tab field type \"" + m.group(2) + "\" for field \"" + name + "\".", e);
        }
        int width = 0;
        int decimals = 0;
        if (m.group(3) != null) {
            final String[] parts = m.group(3).split(",");
            width = Integer.parseInt(parts[0].trim());
            if (parts.length > 1) {
                decimals = Integer.parseInt(parts[1].trim());
            }
        }
        return new TabField(name, type, width, decimals);
    }

    /**
     * Maps MapInfo's {@code !charset} names to a Java {@link Charset}. Only the common Windows code pages are
     * covered; anything unrecognized (or {@code Neutral}, which GDAL's own writer emits as plain UTF-8) falls back
     * to UTF-8.
     */
    private static Charset mapMapInfoCharset(final String name) {
        return switch (name.trim()) {
            case "WindowsLatin1" -> Charset.forName("windows-1252");
            case "WindowsLatin2" -> Charset.forName("windows-1250");
            case "WindowsCyrillic" -> Charset.forName("windows-1251");
            case "WindowsArabic" -> Charset.forName("windows-1256");
            case "WindowsGreek" -> Charset.forName("windows-1253");
            case "WindowsTurkish" -> Charset.forName("windows-1254");
            case "WindowsHebrew" -> Charset.forName("windows-1255");
            case "WindowsSimpChinese" -> Charset.forName("GBK");
            case "WindowsTradChinese" -> Charset.forName("Big5");
            case "WindowsJapanese" -> Charset.forName("Shift_JIS");
            case "WindowsKorean" -> Charset.forName("EUC-KR");
            default -> StandardCharsets.UTF_8;
        };
    }

    // ------------------------------------------------------------------------------------------------------------
    // .dat (attribute) records
    // ------------------------------------------------------------------------------------------------------------

    private static int datFieldByteLength(final TabField field) {
        return switch (field.type()) {
            case CHAR, DECIMAL -> field.width();
            case SMALLINT -> 2;
            case INTEGER, DATE, TIME -> 4;
            case LARGEINT, FLOAT, DATETIME -> 8;
            case LOGICAL -> 1;
        };
    }

    private static List<Object[]> readDatRecords(final byte[] dat, final List<TabField> fields,
        final Charset charset) {
        final ByteBuffer header = ByteBuffer.wrap(dat).order(ByteOrder.LITTLE_ENDIAN);
        final int numRecords = header.getInt(4);
        final int headerLength = header.getShort(8) & 0xFFFF;
        final int recordLength = header.getShort(10) & 0xFFFF;
        final List<Object[]> rows = new ArrayList<>(numRecords);
        int recordStart = headerLength;
        for (int r = 0; r < numRecords; r++) {
            int fieldOffset = recordStart + 1; // skip the 1-byte deletion flag
            final Object[] row = new Object[fields.size()];
            for (int f = 0; f < fields.size(); f++) {
                final TabField field = fields.get(f);
                row[f] = decodeDatField(dat, fieldOffset, field, charset);
                fieldOffset += datFieldByteLength(field);
            }
            rows.add(row);
            recordStart += recordLength;
        }
        return rows;
    }

    /**
     * MapInfo's {@code .dat} is structurally a dBASE-III file, but (per GDAL's own driver comment) every field is
     * declared {@code 'C'} (Char) in the DBF header regardless of its true type - numeric/date/time/logical fields
     * are stored as raw binary inside that nominally-Char byte range rather than as ASCII text. The true type comes
     * only from the {@code .tab} file's own {@code Fields} clause, which is why this reads raw bytes per the
     * {@code .tab}-declared type rather than trusting the {@code .dat} header's own field-type byte.
     */
    private static Object decodeDatField(final byte[] dat, final int offset, final TabField field,
        final Charset charset) {
        final ByteBuffer buf = ByteBuffer.wrap(dat, offset, datFieldByteLength(field)).slice()
            .order(ByteOrder.LITTLE_ENDIAN);
        return switch (field.type()) {
            case CHAR -> trimTrailing(new String(dat, offset, field.width(), charset));
            case DECIMAL -> parseDecimalText(new String(dat, offset, field.width(), StandardCharsets.US_ASCII));
            case SMALLINT -> (int)buf.getShort(0);
            case INTEGER -> buf.getInt(0);
            case LARGEINT -> buf.getLong(0);
            case FLOAT -> buf.getDouble(0);
            case LOGICAL -> dat[offset] != 0;
            case DATE -> decodeDate(buf, 0);
            case TIME -> decodeTimeOfDay(buf.getInt(0));
            case DATETIME -> decodeDateTime(buf);
        };
    }

    private static String trimTrailing(final String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\0' || s.charAt(end - 1) == ' ')) {
            end--;
        }
        return s.substring(0, end);
    }

    private static Double parseDecimalText(final String text) {
        final String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(trimmed);
        } catch (final NumberFormatException e) { // NOSONAR - leave unset on malformed input
            return null;
        }
    }

    /** Empirically confirmed layout: {@code year(uint16 LE) + month(uint8) + day(uint8)}, all-zero means unset. */
    private static LocalDate decodeDate(final ByteBuffer buf, final int offset) {
        final int year = buf.getShort(offset) & 0xFFFF;
        final int month = buf.get(offset + 2) & 0xFF;
        final int day = buf.get(offset + 3) & 0xFF;
        if (year == 0 || month == 0 || day == 0) {
            return null;
        }
        try {
            return LocalDate.of(year, month, day);
        } catch (final DateTimeException e) { // NOSONAR - malformed date bytes, leave unset
            return null;
        }
    }

    /** Empirically confirmed: milliseconds since midnight, as a {@code uint32 LE}. */
    private static LocalTime decodeTimeOfDay(final int millisOfDay) {
        if (millisOfDay < 0 || millisOfDay >= 86_400_000) {
            return null;
        }
        return LocalTime.ofSecondOfDay(millisOfDay / 1000).plusNanos((millisOfDay % 1000) * 1_000_000L);
    }

    /** Empirically confirmed: the 4-byte Date encoding immediately followed by the 4-byte Time encoding. */
    private static LocalDateTime decodeDateTime(final ByteBuffer buf) {
        final LocalDate date = decodeDate(buf, 0);
        if (date == null) {
            return null;
        }
        final LocalTime time = decodeTimeOfDay(buf.getInt(4));
        return LocalDateTime.of(date, time == null ? LocalTime.MIDNIGHT : time);
    }

    private static DataType knimeTypeFor(final TabFieldType type) {
        return switch (type) {
            case CHAR -> StringCell.TYPE;
            case SMALLINT, INTEGER -> IntCell.TYPE;
            case LARGEINT -> LongCell.TYPE;
            case DECIMAL, FLOAT -> DoubleCell.TYPE;
            case LOGICAL -> BooleanCell.TYPE;
            case DATE -> LocalDateCellFactory.TYPE;
            case TIME -> LocalTimeCellFactory.TYPE;
            case DATETIME -> LocalDateTimeCellFactory.TYPE;
        };
    }

    private static DataCell toDataCell(final Object value, final TabFieldType type) {
        if (value == null) {
            return DataType.getMissingCell();
        }
        return switch (type) {
            case CHAR -> new StringCell((String)value);
            case SMALLINT, INTEGER -> new IntCell((Integer)value);
            case LARGEINT -> new LongCell((Long)value);
            case DECIMAL, FLOAT -> new DoubleCell((Double)value);
            case LOGICAL -> BooleanCell.get((Boolean)value);
            case DATE -> LocalDateCellFactory.create((LocalDate)value);
            case TIME -> LocalTimeCellFactory.create((LocalTime)value);
            case DATETIME -> LocalDateTimeCellFactory.create((LocalDateTime)value);
        };
    }

    // ------------------------------------------------------------------------------------------------------------
    // .id (row -> .map byte offset)
    // ------------------------------------------------------------------------------------------------------------

    /** Empirically confirmed: a flat array of {@code int32 LE}, 1 per row, 0 meaning "no geometry". */
    private static long readIdOffset(final byte[] id, final int rowIndex) {
        final int offset = rowIndex * 4;
        if (offset + 4 > id.length) {
            return 0;
        }
        return ByteBuffer.wrap(id, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
    }

    // ------------------------------------------------------------------------------------------------------------
    // .map header
    // ------------------------------------------------------------------------------------------------------------

    record MapHeader(double xScale, double yScale, double xDispl, double yDispl, int coordOriginQuadrant,
        int regularBlockSize) {
    }

    private static MapHeader parseMapHeader(final byte[] map) throws KNIMEException {
        final ByteBuffer buf = ByteBuffer.wrap(map).order(ByteOrder.LITTLE_ENDIAN);
        if (buf.getInt(0x100) != 42_424_242) {
            throw new KNIMEException("Not a valid MapInfo .map file (bad header magic cookie).");
        }
        final int regularBlockSize = buf.getShort(0x106) & 0xFFFF;
        final double xScale = buf.getDouble(0x170);
        final double yScale = buf.getDouble(0x178);
        final double xDispl = buf.getDouble(0x180);
        final double yDispl = buf.getDouble(0x188);
        final int quadrant = map[0x161] & 0xFF;
        return new MapHeader(xScale, yScale, xDispl, yDispl, quadrant, regularBlockSize <= 0 ? 512 : regularBlockSize);
    }

    /**
     * GDAL's {@code Int2Coordsys} transform - the sign flips depending on which "quadrant" the file was set up in.
     * Verified exact for the common case (geographic lon/lat data, where the writer picks a clean scale like
     * {@code 1e6}). For large-magnitude projected coordinates where the writer computes a non-round scale to fit
     * the value range into a signed 32-bit integer, this can differ from GDAL's own result in the last 1-2
     * significant digits (confirmed empirically: real coordinates around 5,200,000 differed by ~0.005) - ordinary
     * double-precision division noise, not a wrong formula, and far below the precision the integer encoding itself
     * can represent at that magnitude regardless of which library reads it.
     */
    private static double intToCoordX(final int raw, final MapHeader h) {
        return h.coordOriginQuadrant() == 2 || h.coordOriginQuadrant() == 3 || h.coordOriginQuadrant() == 0
            ? -1.0 * (raw + h.xDispl()) / h.xScale() : (raw - h.xDispl()) / h.xScale();
    }

    private static double intToCoordY(final int raw, final MapHeader h) {
        return h.coordOriginQuadrant() == 3 || h.coordOriginQuadrant() == 4 || h.coordOriginQuadrant() == 0
            ? -1.0 * (raw + h.yDispl()) / h.yScale() : (raw - h.yDispl()) / h.yScale();
    }

    // ------------------------------------------------------------------------------------------------------------
    // .map objects (geometry)
    // ------------------------------------------------------------------------------------------------------------

    private static boolean isCompressed(final int typeCode) {
        return typeCode % 3 == 1;
    }

    private enum RegionVersion {
            V300, V450, V800
    }

    /** {@code TAB_GEOM_GET_VERSION}: which byte layout a Region/Multiline-family type code uses. */
    private static RegionVersion regionVersionFor(final int typeCode) {
        if (typeCode < TYPE_V450_REGION_C) {
            return RegionVersion.V300;
        } else if (typeCode < TYPE_V800_REGION_C) {
            return RegionVersion.V450;
        }
        return RegionVersion.V800;
    }

    record ObjectBlockHeader(int centerX, int centerY) {
    }

    private static ObjectBlockHeader readObjectBlockHeader(final byte[] map, final int blockStart) {
        final ByteBuffer buf = ByteBuffer.wrap(map, blockStart, OBJECT_BLOCK_HEADER_SIZE).slice()
            .order(ByteOrder.LITTLE_ENDIAN);
        return new ObjectBlockHeader(buf.getInt(4), buf.getInt(8));
    }

    private static int findEnclosingBlockStart(final int objOffset, final int regularBlockSize) {
        final int relative = objOffset - HEADER_BLOCK_SIZE;
        return HEADER_BLOCK_SIZE + (relative / regularBlockSize) * regularBlockSize;
    }

    private static Geometry readGeometryAt(final byte[] map, final int objOffset, final MapHeader mapHeader)
        throws KNIMEException {
        final int typeCode = map[objOffset] & 0xFF;
        if (typeCode == TYPE_NONE) {
            return null;
        }
        final boolean compressed = isCompressed(typeCode);
        final int blockStart = findEnclosingBlockStart(objOffset, mapHeader.regularBlockSize());
        final ObjectBlockHeader blockHeader = readObjectBlockHeader(map, blockStart);
        final ByteBuffer body = ByteBuffer.wrap(map, objOffset + 5, map.length - objOffset - 5).slice()
            .order(ByteOrder.LITTLE_ENDIAN);

        return switch (typeCode) {
            case TYPE_SYMBOL_C, TYPE_SYMBOL -> readPoint(body, compressed, blockHeader, mapHeader);
            case TYPE_LINE_C, TYPE_LINE -> readSimpleLine(body, compressed, blockHeader, mapHeader);
            case TYPE_PLINE_C, TYPE_PLINE -> readPolyline(map, body, compressed, mapHeader);
            case TYPE_REGION_C, TYPE_REGION, TYPE_V450_REGION_C, TYPE_V450_REGION, TYPE_V800_REGION_C,
                TYPE_V800_REGION ->
                readRegionOrMultiline(map, body, compressed, mapHeader, true, regionVersionFor(typeCode));
            case TYPE_MULTIPLINE_C, TYPE_MULTIPLINE, TYPE_V450_MULTIPLINE_C, TYPE_V450_MULTIPLINE,
                TYPE_V800_MULTIPLINE_C, TYPE_V800_MULTIPLINE ->
                readRegionOrMultiline(map, body, compressed, mapHeader, false, regionVersionFor(typeCode));
            default -> throw new KNIMEException("Unsupported MapInfo TAB geometry object type 0x"
                + Integer.toHexString(typeCode) + " - only Point, Line, Polyline and Region/Polygon objects are "
                + "currently supported.");
        };
    }

    /** {@code X,Y} (int32 pair, or int16 delta from the object block's own center if compressed) + 1-byte symbolId. */
    private static Point readPoint(final ByteBuffer body, final boolean compressed, final ObjectBlockHeader block,
        final MapHeader mapHeader) {
        final int rawX;
        final int rawY;
        if (compressed) {
            rawX = block.centerX() + body.getShort(0);
            rawY = block.centerY() + body.getShort(2);
        } else {
            rawX = body.getInt(0);
            rawY = body.getInt(4);
        }
        return GEOMETRY_FACTORY
            .createPoint(new Coordinate(intToCoordX(rawX, mapHeader), intToCoordY(rawY, mapHeader)));
    }

    /** {@code X1,Y1,X2,Y2} (coord pairs, inline - no coordinate block) + 1-byte penId. */
    private static LineString readSimpleLine(final ByteBuffer body, final boolean compressed,
        final ObjectBlockHeader block, final MapHeader mapHeader) {
        final int x1;
        final int y1;
        final int x2;
        final int y2;
        if (compressed) {
            x1 = block.centerX() + body.getShort(0);
            y1 = block.centerY() + body.getShort(2);
            x2 = block.centerX() + body.getShort(4);
            y2 = block.centerY() + body.getShort(6);
        } else {
            x1 = body.getInt(0);
            y1 = body.getInt(4);
            x2 = body.getInt(8);
            y2 = body.getInt(12);
        }
        return GEOMETRY_FACTORY.createLineString(new Coordinate[]{
            new Coordinate(intToCoordX(x1, mapHeader), intToCoordY(y1, mapHeader)),
            new Coordinate(intToCoordX(x2, mapHeader), intToCoordY(y2, mapHeader))});
    }

    /** Single-section polyline: {@code coordBlockPtr, coordDataSize}, then label/MBR/pen (unused for the geometry). */
    private static LineString readPolyline(final byte[] map, final ByteBuffer body, final boolean compressed,
        final MapHeader mapHeader) throws KNIMEException {
        final int coordBlockPtr = body.getInt(0);
        final int coordDataSize = body.getInt(4) & 0x7FFFFFFF;
        int comprOrgX = 0;
        int comprOrgY = 0;
        if (compressed) {
            // labelX,labelY (int16 pair) precede comprOrgX,comprOrgY (int32 pair) - only present when compressed.
            comprOrgX = body.getInt(8 + 4);
            comprOrgY = body.getInt(8 + 4 + 4);
        }
        final int vertexSize = compressed ? 4 : 8;
        final Coordinate[] coords = readVertexRun(map, coordBlockPtr, 0, coordDataSize / vertexSize, compressed,
            mapHeader, comprOrgX, comprOrgY);
        return GEOMETRY_FACTORY.createLineString(coords);
    }

    /**
     * Multi-section Region (polygon, possibly multiple parts/holes) or Multiline (polyline, possibly multiple
     * parts) object. Only the uncompressed form is supported - see the class javadoc for why the compressed form is
     * deliberately rejected rather than guessed at.
     */
    private static Geometry readRegionOrMultiline(final byte[] map, final ByteBuffer body, final boolean compressed,
        final MapHeader mapHeader, final boolean isRegion, final RegionVersion version) throws KNIMEException {
        if (compressed) {
            throw new KNIMEException("Compressed multi-section MapInfo TAB " + (isRegion ? "Region" : "Multiline")
                + " objects are not supported by this node.");
        }
        final int coordBlockPtr = body.getInt(0);
        final int numSections = version == RegionVersion.V800 ? body.getInt(8) : body.getShort(8) & 0xFFFF;

        final List<int[]> sections = readSectionHeaders(map, coordBlockPtr, numSections, version);

        if (!isRegion) {
            final List<LineString> parts = new ArrayList<>();
            for (final int[] section : sections) {
                parts.add(GEOMETRY_FACTORY.createLineString(
                    readVertexRun(map, coordBlockPtr, section[2], section[0], false, mapHeader, 0, 0)));
            }
            return parts.size() == 1 ? parts.get(0)
                : GEOMETRY_FACTORY.createMultiLineString(parts.toArray(new LineString[0]));
        }

        final List<Polygon> polygons = new ArrayList<>();
        int i = 0;
        while (i < sections.size()) {
            final int[] shellInfo = sections.get(i);
            final LinearRing shell = GEOMETRY_FACTORY.createLinearRing(
                readVertexRun(map, coordBlockPtr, shellInfo[2], shellInfo[0], false, mapHeader, 0, 0));
            final int numHoles = shellInfo[1];
            final LinearRing[] holes = new LinearRing[numHoles];
            for (int h = 0; h < numHoles; h++) {
                final int[] holeInfo = sections.get(i + 1 + h);
                holes[h] = GEOMETRY_FACTORY.createLinearRing(
                    readVertexRun(map, coordBlockPtr, holeInfo[2], holeInfo[0], false, mapHeader, 0, 0));
            }
            polygons.add(GEOMETRY_FACTORY.createPolygon(shell, holes));
            i += 1 + numHoles;
        }
        return polygons.size() == 1 ? polygons.get(0)
            : GEOMETRY_FACTORY.createMultiPolygon(polygons.toArray(new Polygon[0]));
    }

    /**
     * Reads {@code numSections} {@code TABMAPCoordSecHdr} records starting at {@code coordBlockPtr} (all packed
     * contiguously - the same assumption GDAL's own reader makes, per its source comments). Each entry is
     * {@code {numVertices, numHoles, vertexDataOffset (relative to coordBlockPtr)}}.
     */
    private static List<int[]> readSectionHeaders(final byte[] map, final int coordBlockPtr, final int numSections,
        final RegionVersion version) {
        final int headerSize = version == RegionVersion.V300 ? 24 : 28;
        final List<int[]> sections = new ArrayList<>(numSections);
        int off = coordBlockPtr;
        for (int s = 0; s < numSections; s++) {
            final int numVertices;
            final int numHoles;
            if (version == RegionVersion.V300) {
                numVertices = readUint16LE(map, off);
                numHoles = readUint16LE(map, off + 2);
            } else {
                numVertices = readInt32LE(map, off);
                numHoles = readInt32LE(map, off + 4);
            }
            final int dataOffset = readInt32LE(map, off + headerSize - 4);
            sections.add(new int[]{numVertices, numHoles, dataOffset});
            off += headerSize;
        }
        return sections;
    }

    /**
     * Reads {@code numVertices} coordinate pairs starting at the virtual offset {@code virtualOffset} - relative to
     * {@code coordBlockPtr}, i.e. exactly the {@code dataOffset}/{@code coordBlockPtr} values as stored in the
     * object/section headers - within the (possibly chained) coordinate-block stream, transparently following
     * {@code nNextCoordBlock} for both the seek and the read if either crosses a block boundary.
     */
    private static Coordinate[] readVertexRun(final byte[] map, final int coordBlockPtr, final int virtualOffset,
        final int numVertices, final boolean compressed, final MapHeader mapHeader, final int comprOrgX,
        final int comprOrgY) {
        final int vertexSize = compressed ? 4 : 8;
        final byte[] bytes = readCoordBytes(map, coordBlockPtr, virtualOffset, numVertices * vertexSize);
        final ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        final Coordinate[] coords = new Coordinate[numVertices];
        for (int v = 0; v < numVertices; v++) {
            final int rawX;
            final int rawY;
            if (compressed) {
                rawX = comprOrgX + buf.getShort(v * 4);
                rawY = comprOrgY + buf.getShort(v * 4 + 2);
            } else {
                rawX = buf.getInt(v * 8);
                rawY = buf.getInt(v * 8 + 4);
            }
            coords[v] = new Coordinate(intToCoordX(rawX, mapHeader), intToCoordY(rawY, mapHeader));
        }
        return coords;
    }

    /**
     * Reads {@code length} bytes starting at virtual offset {@code virtualOffset} (relative to {@code coordBlockPtr}
     * - i.e. this walks the coordinate-block chain from its first block to find the physical block/position the
     * virtual offset actually falls in, which is NOT necessarily the first block's own data start: a Region/
     * Multiline's later sections' {@code dataOffset} values point partway into the (possibly chained) data stream,
     * not to a fresh block boundary). Mirrors GDAL's {@code TABMAPCoordBlock::ReadBytes} chaining, generalized to an
     * arbitrary starting position rather than always the stream's very beginning.
     */
    private static byte[] readCoordBytes(final byte[] map, final int coordBlockPtr, final int virtualOffset,
        final int length) {
        final byte[] result = new byte[length];
        int written = 0;
        int blockDataStart = coordBlockPtr;
        int toSkip = virtualOffset;
        while (written < length) {
            final int blockStart = blockDataStart - COORD_BLOCK_HEADER_SIZE;
            final int blockDataBytes = readUint16LE(map, blockStart + 2);
            final int nextCoordBlock = readInt32LE(map, blockStart + 4);
            if (toSkip < blockDataBytes) {
                final int available = blockDataBytes - toSkip;
                final int toCopy = Math.min(available, length - written);
                System.arraycopy(map, blockDataStart + toSkip, result, written, toCopy);
                written += toCopy;
                toSkip = 0;
            } else {
                toSkip -= blockDataBytes;
            }
            if (written < length) {
                if (nextCoordBlock == 0) {
                    throw new IllegalStateException("Unexpected end of MapInfo TAB coordinate block chain.");
                }
                blockDataStart = nextCoordBlock + COORD_BLOCK_HEADER_SIZE;
            }
        }
        return result;
    }

    private static int readInt32LE(final byte[] arr, final int offset) {
        return ByteBuffer.wrap(arr, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static int readUint16LE(final byte[] arr, final int offset) {
        return ByteBuffer.wrap(arr, offset, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
    }

    /**
     * MapInfo TAB has no CRS concept in the {@code .map}/{@code .dat}/{@code .id} files themselves (only the
     * {@code .tab} text may carry an optional {@code CoordSys} clause, which this reader does not currently parse
     * for CRS identification) - tags every geometry with {@link GeoReferenceSystem#DEFAULT}, the same fallback the
     * GeoParquet reader uses when no CRS metadata is present.
     */
    private static DataCell toGeoCell(final Geometry geometry) throws KNIMEException {
        if (geometry == null) {
            return DataType.getMissingCell();
        }
        try {
            final byte[] wkb = new WKBWriter(2).write(geometry);
            return GeoCellFactory.create(wkb, GeoReferenceSystem.DEFAULT);
        } catch (final Exception e) {
            throw KNIMEException.of(Message.fromSummary("Could not create geometry cell: " + e.getMessage()), e);
        }
    }
}

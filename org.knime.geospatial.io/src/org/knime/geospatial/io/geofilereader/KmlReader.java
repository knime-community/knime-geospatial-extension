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

import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.knime.core.data.DataCell;
import org.knime.core.data.DataColumnSpec;
import org.knime.core.data.DataColumnSpecCreator;
import org.knime.core.data.DataTableSpec;
import org.knime.core.data.DataType;
import org.knime.core.data.RowKey;
import org.knime.core.data.def.DefaultRow;
import org.knime.core.data.def.DoubleCell;
import org.knime.core.data.def.IntCell;
import org.knime.core.data.def.StringCell;
import org.knime.core.data.time.localdatetime.LocalDateTimeCellFactory;
import org.knime.core.node.BufferedDataContainer;
import org.knime.core.node.BufferedDataTable;
import org.knime.core.node.ExecutionContext;
import org.knime.core.node.KNIMEException;
import org.knime.core.node.message.Message;
import org.knime.geospatial.core.data.cell.GeoCell;
import org.knime.geospatial.core.data.cell.GeoCellFactory;
import org.knime.geospatial.core.data.reference.GeoReferenceSystem;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.WKBWriter;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Hand-rolled KML/KMZ reader matching the exact column set, ordering, types and defaults produced by GDAL's OGR KML
 * driver - the library the Python {@code GeoFileReaderNode} actually reads KML/KMZ through, via {@code fiona}/
 * {@code geopandas}. GeoTools' own {@code gt-xsd-kml} binding (used by this bundle previously) produces a completely
 * different, KML-XSD-shaped schema (a fixed {@code name}/{@code visibility}/{@code open}/{@code address}/
 * {@code phoneNumber}/{@code description} attribute set) and silently drops {@code ExtendedData} entirely, so it
 * cannot be reused to match Python's output.
 * <p>
 * Every rule below - the fixed 12-column core schema, its exact defaults (e.g. {@code tessellate}/{@code visibility}
 * default to {@code -1} rather than {@code 0}), which geometry elements accept {@code tessellate} vs {@code extrude},
 * layer-boundary detection, and the fact that real GDAL leaves {@code icon} unpopulated even for an inline
 * {@code IconStyle} - was captured empirically against a local geopandas/fiona/GDAL install reading hand-written
 * sample KML files, not guessed from documentation.
 * <p>
 * Deliberately out of scope, matching real GDAL behavior observed during that verification: only the first "layer"
 * (the first {@code Document}/{@code Folder} in document order with direct {@code Placemark} children) is read,
 * exactly like {@code gp.read_file(..., driver="KML")} without an explicit {@code layer=} argument - a later
 * Folder's placemarks are not merged in, matching the Python node's own unresolved TODO ("Create combined schema").
 * {@code styleUrl}-referenced shared {@code Style} definitions are not resolved for the {@code icon} column (real
 * GDAL leaves it {@code null} even for an inline {@code IconStyle}, so there is nothing to match). {@code gx:}
 * extension geometries ({@code gx:Track} etc.) and {@code Model} placemarks are not supported, the same boundary
 * already documented for the rest of this bundle's KML handling.
 */
final class KmlReader {

    private static final Set<String> GEOMETRY_NAMES =
        Set.of("Point", "LineString", "LinearRing", "Polygon", "MultiGeometry");

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    private KmlReader() {
        // utility class
    }

    static BufferedDataTable read(final InputStream kmlContent, final ExecutionContext exec) throws Exception {
        final Document doc = parse(kmlContent);
        final Element layer = findFirstLayer(doc.getDocumentElement());
        if (layer == null) {
            throw new KNIMEException("The KML file contains no placemarks.");
        }

        final List<Placemark> placemarks = new ArrayList<>();
        final Set<String> extendedFields = new LinkedHashSet<>();
        for (final Element pmElement : directChildElements(layer, "Placemark")) {
            final Placemark placemark = parsePlacemark(pmElement);
            placemarks.add(placemark);
            extendedFields.addAll(placemark.extendedData.keySet());
        }
        if (placemarks.isEmpty()) {
            throw new KNIMEException("The KML file contains no placemarks.");
        }

        final DataTableSpec spec = buildSpec(extendedFields);
        final BufferedDataContainer container = exec.createDataContainer(spec, false);
        long rowIdx = 0;
        for (final Placemark placemark : placemarks) {
            exec.checkCanceled();
            container.addRowToTable(new DefaultRow(RowKey.createRowKey(rowIdx++), buildRow(placemark, extendedFields)));
        }
        container.close();
        return container.getTable();
    }

    private static Document parse(final InputStream in) throws Exception {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        final DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(in);
    }

    /**
     * Depth-first, document-order search for the first {@code Document}/{@code Folder} element with at least one
     * direct {@code Placemark} child - matches real GDAL's layer boundaries: every container that directly holds
     * Placemarks is its own layer, a nested Folder's placemarks never bleed into its parent's layer, and reading
     * "the file" without picking a layer means reading only the first such layer encountered.
     */
    private static Element findFirstLayer(final Element root) {
        if (isContainer(root) && !directChildElements(root, "Placemark").isEmpty()) {
            return root;
        }
        for (final Element child : childElements(root)) {
            final Element found = findFirstLayer(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static boolean isContainer(final Element element) {
        final String name = localName(element);
        return "Document".equals(name) || "Folder".equals(name) || "kml".equals(name);
    }

    private static final class Placemark {
        String id;
        String name;
        String description;
        LocalDateTime timestamp;
        LocalDateTime begin;
        LocalDateTime end;
        String altitudeMode;
        int tessellate = -1;
        int extrude;
        int visibility = -1;
        Double drawOrder;
        Geometry geometry;
        Map<String, String> extendedData = new LinkedHashMap<>();
    }

    private static Placemark parsePlacemark(final Element pm) {
        final Placemark p = new Placemark();
        p.id = pm.hasAttribute("id") ? pm.getAttribute("id") : null;
        p.name = textOf(firstDirectChild(pm, "name"));
        p.description = textOf(firstDirectChild(pm, "description"));

        final Element timeStamp = firstDirectChild(pm, "TimeStamp");
        if (timeStamp != null) {
            p.timestamp = parseKmlDateTime(textOf(firstDirectChild(timeStamp, "when")));
        }
        final Element timeSpan = firstDirectChild(pm, "TimeSpan");
        if (timeSpan != null) {
            p.begin = parseKmlDateTime(textOf(firstDirectChild(timeSpan, "begin")));
            p.end = parseKmlDateTime(textOf(firstDirectChild(timeSpan, "end")));
        }

        p.visibility = parseIntOrDefault(textOf(firstDirectChild(pm, "visibility")), -1);
        final String drawOrderText = textOf(firstDirectChild(pm, "drawOrder"));
        if (drawOrderText != null && !drawOrderText.isEmpty()) {
            try {
                p.drawOrder = Double.parseDouble(drawOrderText);
            } catch (final NumberFormatException e) { // NOSONAR - leave unset on malformed input
                // ignore
            }
        }

        final Element geomElement = findGeometryElement(pm);
        if (geomElement != null) {
            p.geometry = parseGeometry(geomElement);
            final String geomName = localName(geomElement);
            // Per the KML 2.2 schema, extrude is valid on Point/LineString/LinearRing/Polygon but tessellate only on
            // LineString/LinearRing/Polygon (not Point) - MultiGeometry itself carries neither, only its members do,
            // and OGR's flat schema has no way to surface per-member modifiers, so both stay at their defaults.
            if (!"MultiGeometry".equals(geomName)) {
                p.altitudeMode = textOf(firstDirectChild(geomElement, "altitudeMode"));
                p.extrude = parseIntOrDefault(textOf(firstDirectChild(geomElement, "extrude")), 0);
                if (!"Point".equals(geomName)) {
                    p.tessellate = parseIntOrDefault(textOf(firstDirectChild(geomElement, "tessellate")), -1);
                }
            }
        }

        final Element extendedData = firstDirectChild(pm, "ExtendedData");
        if (extendedData != null) {
            collectExtendedData(extendedData, p.extendedData);
        }
        return p;
    }

    private static int parseIntOrDefault(final String text, final int fallback) {
        if (text == null || text.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (final NumberFormatException e) { // NOSONAR - fall back on malformed input
            return fallback;
        }
    }

    private static void collectExtendedData(final Element extendedData, final Map<String, String> out) {
        for (final Element dataEl : directChildElements(extendedData, "Data")) {
            final String name = dataEl.getAttribute("name");
            if (!name.isEmpty()) {
                out.put(name, textOf(firstDirectChild(dataEl, "value")));
            }
        }
        for (final Element schemaData : directChildElements(extendedData, "SchemaData")) {
            for (final Element simpleData : directChildElements(schemaData, "SimpleData")) {
                final String name = simpleData.getAttribute("name");
                if (!name.isEmpty()) {
                    out.put(name, textOf(simpleData));
                }
            }
        }
    }

    private static LocalDateTime parseKmlDateTime(final String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        final String trimmed = text.trim();
        try {
            return OffsetDateTime.parse(trimmed).toLocalDateTime();
        } catch (final DateTimeParseException e) { // NOSONAR - falling through to the next format is intentional
            // fall through
        }
        try {
            return LocalDateTime.parse(trimmed);
        } catch (final DateTimeParseException e) { // NOSONAR
            // fall through
        }
        try {
            return LocalDate.parse(trimmed).atStartOfDay();
        } catch (final DateTimeParseException e) { // NOSONAR - genuinely unparseable, leave unset
            return null;
        }
    }

    private static Element findGeometryElement(final Element placemark) {
        for (final Element child : childElements(placemark)) {
            if (GEOMETRY_NAMES.contains(localName(child))) {
                return child;
            }
        }
        return null;
    }

    private static Geometry parseGeometry(final Element element) {
        return switch (localName(element)) {
            case "Point" -> parsePoint(element);
            case "LineString", "LinearRing" -> parseLineString(element);
            case "Polygon" -> parsePolygon(element);
            case "MultiGeometry" -> parseMultiGeometry(element);
            default -> null;
        };
    }

    private static Point parsePoint(final Element element) {
        final Coordinate[] coords = parseCoordinates(firstDirectChild(element, "coordinates"));
        return coords.length == 0 ? null : GEOMETRY_FACTORY.createPoint(coords[0]);
    }

    private static LineString parseLineString(final Element element) {
        return GEOMETRY_FACTORY.createLineString(parseCoordinates(firstDirectChild(element, "coordinates")));
    }

    private static LinearRing parseLinearRing(final Element element) {
        return GEOMETRY_FACTORY.createLinearRing(parseCoordinates(firstDirectChild(element, "coordinates")));
    }

    private static Polygon parsePolygon(final Element element) {
        final Element outerBoundary = firstDirectChild(element, "outerBoundaryIs");
        final LinearRing shell = parseLinearRing(firstDirectChild(outerBoundary, "LinearRing"));
        final List<Element> innerBoundaries = directChildElements(element, "innerBoundaryIs");
        final LinearRing[] holes = new LinearRing[innerBoundaries.size()];
        for (int i = 0; i < innerBoundaries.size(); i++) {
            holes[i] = parseLinearRing(firstDirectChild(innerBoundaries.get(i), "LinearRing"));
        }
        return GEOMETRY_FACTORY.createPolygon(shell, holes);
    }

    /** Collapses to the matching {@code Multi*} type when every member shares a type, mirroring real GDAL/OGR. */
    private static Geometry parseMultiGeometry(final Element element) {
        final List<Geometry> parts = new ArrayList<>();
        for (final Element child : childElements(element)) {
            if (GEOMETRY_NAMES.contains(localName(child))) {
                final Geometry g = parseGeometry(child);
                if (g != null) {
                    parts.add(g);
                }
            }
        }
        if (parts.isEmpty()) {
            return null;
        }
        if (parts.stream().allMatch(Point.class::isInstance)) {
            return GEOMETRY_FACTORY.createMultiPoint(parts.toArray(new Point[0]));
        } else if (parts.stream().allMatch(LineString.class::isInstance)) {
            return GEOMETRY_FACTORY.createMultiLineString(parts.toArray(new LineString[0]));
        } else if (parts.stream().allMatch(Polygon.class::isInstance)) {
            return GEOMETRY_FACTORY.createMultiPolygon(parts.toArray(new Polygon[0]));
        }
        return GEOMETRY_FACTORY.createGeometryCollection(parts.toArray(new Geometry[0]));
    }

    /** KML {@code <coordinates>} text: whitespace-separated {@code lon,lat[,alt]} tuples, altitude optional. */
    private static Coordinate[] parseCoordinates(final Element coordinatesElement) {
        if (coordinatesElement == null) {
            return new Coordinate[0];
        }
        final String text = coordinatesElement.getTextContent().trim();
        if (text.isEmpty()) {
            return new Coordinate[0];
        }
        final String[] tuples = text.split("\\s+");
        final Coordinate[] coords = new Coordinate[tuples.length];
        for (int i = 0; i < tuples.length; i++) {
            final String[] parts = tuples[i].split(",");
            final double lon = Double.parseDouble(parts[0]);
            final double lat = Double.parseDouble(parts[1]);
            coords[i] = parts.length > 2 && !parts[2].isEmpty() ? new Coordinate(lon, lat, Double.parseDouble(parts[2]))
                : new Coordinate(lon, lat);
        }
        return coords;
    }

    private static DataTableSpec buildSpec(final Set<String> extendedFields) {
        final List<DataColumnSpec> specs = new ArrayList<>();
        specs.add(col("id", StringCell.TYPE));
        specs.add(col("Name", StringCell.TYPE));
        specs.add(col("description", StringCell.TYPE));
        specs.add(col("timestamp", LocalDateTimeCellFactory.TYPE));
        specs.add(col("begin", LocalDateTimeCellFactory.TYPE));
        specs.add(col("end", LocalDateTimeCellFactory.TYPE));
        specs.add(col("altitudeMode", StringCell.TYPE));
        specs.add(col("tessellate", IntCell.TYPE));
        specs.add(col("extrude", IntCell.TYPE));
        specs.add(col("visibility", IntCell.TYPE));
        specs.add(col("drawOrder", DoubleCell.TYPE));
        specs.add(col("icon", StringCell.TYPE));
        for (final String field : extendedFields) {
            specs.add(col(field, StringCell.TYPE));
        }
        specs.add(col("geometry", GeoCell.TYPE));
        return new DataTableSpec(specs.toArray(new DataColumnSpec[0]));
    }

    private static DataColumnSpec col(final String name, final DataType type) {
        return new DataColumnSpecCreator(name, type).createSpec();
    }

    private static DataCell[] buildRow(final Placemark p, final Set<String> extendedFields) throws KNIMEException {
        final List<DataCell> cells = new ArrayList<>();
        cells.add(stringOrMissing(p.id));
        cells.add(stringOrMissing(p.name));
        cells.add(stringOrMissing(p.description));
        cells.add(p.timestamp == null ? DataType.getMissingCell() : LocalDateTimeCellFactory.create(p.timestamp));
        cells.add(p.begin == null ? DataType.getMissingCell() : LocalDateTimeCellFactory.create(p.begin));
        cells.add(p.end == null ? DataType.getMissingCell() : LocalDateTimeCellFactory.create(p.end));
        cells.add(stringOrMissing(p.altitudeMode));
        cells.add(new IntCell(p.tessellate));
        cells.add(new IntCell(p.extrude));
        cells.add(new IntCell(p.visibility));
        cells.add(p.drawOrder == null ? DataType.getMissingCell() : new DoubleCell(p.drawOrder));
        // icon: real GDAL leaves this column unpopulated too (verified empirically, inline IconStyle included).
        cells.add(DataType.getMissingCell());
        for (final String field : extendedFields) {
            cells.add(stringOrMissing(p.extendedData.get(field)));
        }
        cells.add(toGeoCell(p.geometry));
        return cells.toArray(new DataCell[0]);
    }

    private static DataCell stringOrMissing(final String value) {
        return value == null ? DataType.getMissingCell() : new StringCell(value);
    }

    /**
     * KML has no CRS concept at all - coordinates are always lon/lat WGS84 - so this always tags the geometry with
     * {@link GeoReferenceSystem#DEFAULT}, mirroring how the GeoParquet reader falls back to the same default.
     * Preserves Z (altitude) when present, unlike {@code GeoTypeMapping#toGeoCell} which always writes 2D WKB - real
     * GDAL keeps a KML placemark's altitude when its {@code <coordinates>} include one.
     */
    private static DataCell toGeoCell(final Geometry geometry) throws KNIMEException {
        if (geometry == null) {
            return DataType.getMissingCell();
        }
        try {
            final byte[] wkb = new WKBWriter(hasZ(geometry) ? 3 : 2).write(geometry);
            return GeoCellFactory.create(wkb, GeoReferenceSystem.DEFAULT);
        } catch (final Exception e) {
            throw KNIMEException.of(Message.fromSummary("Could not create geometry cell: " + e.getMessage()), e);
        }
    }

    private static boolean hasZ(final Geometry geometry) {
        for (final Coordinate c : geometry.getCoordinates()) {
            if (!Double.isNaN(c.getZ())) {
                return true;
            }
        }
        return false;
    }

    private static List<Element> directChildElements(final Element parent, final String localName) {
        final List<Element> result = new ArrayList<>();
        final NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            final Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && localName.equals(localName((Element)child))) {
                result.add((Element)child);
            }
        }
        return result;
    }

    private static List<Element> childElements(final Element parent) {
        final List<Element> result = new ArrayList<>();
        final NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            final Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                result.add((Element)child);
            }
        }
        return result;
    }

    private static Element firstDirectChild(final Element parent, final String localName) {
        if (parent == null) {
            return null;
        }
        final List<Element> matches = directChildElements(parent, localName);
        return matches.isEmpty() ? null : matches.get(0);
    }

    private static String localName(final Element element) {
        final String ln = element.getLocalName();
        return ln != null ? ln : element.getNodeName();
    }

    private static String textOf(final Element element) {
        return element == null ? null : element.getTextContent().trim();
    }
}

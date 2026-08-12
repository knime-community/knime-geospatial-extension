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
package org.knime.geospatial.io.util;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.api.feature.type.AttributeDescriptor;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.referencing.CRS;
import org.knime.core.data.DataCell;
import org.knime.core.data.DataColumnSpec;
import org.knime.core.data.DataColumnSpecCreator;
import org.knime.core.data.DataRow;
import org.knime.core.data.DataTableSpec;
import org.knime.core.data.DataType;
import org.knime.core.data.def.BooleanCell;
import org.knime.core.data.def.DoubleCell;
import org.knime.core.data.def.IntCell;
import org.knime.core.data.def.LongCell;
import org.knime.core.data.def.StringCell;
import org.knime.core.data.time.localdate.LocalDateCellFactory;
import org.knime.core.data.time.localdatetime.LocalDateTimeCellFactory;
import org.knime.core.node.BufferedDataTable;
import org.knime.core.node.KNIMEException;
import org.knime.core.node.message.Message;
import org.knime.geospatial.core.data.GeoValue;
import org.knime.geospatial.core.data.cell.GeoCellFactory;
import org.knime.geospatial.core.data.reference.GeoReferenceSystem;
import org.knime.geospatial.core.data.reference.GeoReferenceSystemFactory;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKBReader;
import org.locationtech.jts.io.WKBWriter;
import org.locationtech.jts.io.ParseException;

/**
 * Maps between GeoTools {@link SimpleFeature} attributes and KNIME {@link DataCell}s/{@link DataType}s, in both
 * directions, so all four nodes share one place for this logic. Geometry values are bridged through
 * {@link GeoCellFactory} / {@link GeoValue#getWKB()} so the resulting KNIME column is the exact same logical type
 * the Python-based Geospatial Analytics Extension's nodes already produce and consume.
 */
public final class GeoTypeMapping {

    private GeoTypeMapping() {
        // utility class
    }

    /**
     * Builds the KNIME {@link DataTableSpec} for a GeoTools {@link SimpleFeatureType}: one column per non-geometry
     * attribute (typed via {@link #knimeTypeFor(Class)}), plus one geometry column named after the feature type's
     * default geometry descriptor.
     */
    public static DataTableSpec toTableSpec(final SimpleFeatureType featureType) {
        final var colSpecs = new DataColumnSpecCreator[featureType.getAttributeCount()];
        int i = 0;
        for (final AttributeDescriptor attr : featureType.getAttributeDescriptors()) {
            final String name = attr.getLocalName();
            final DataType type = Geometry.class.isAssignableFrom(attr.getType().getBinding())
                ? org.knime.geospatial.core.data.cell.GeoCell.TYPE : knimeTypeFor(attr.getType().getBinding());
            colSpecs[i++] = new DataColumnSpecCreator(name, type);
        }
        final var specs = new DataColumnSpec[colSpecs.length];
        for (int j = 0; j < colSpecs.length; j++) {
            specs[j] = colSpecs[j].createSpec();
        }
        return new DataTableSpec(specs);
    }

    /** Maps a GeoTools/Java attribute binding class to the KNIME {@link DataType} to store it as. */
    public static DataType knimeTypeFor(final Class<?> binding) {
        if (String.class.isAssignableFrom(binding)) {
            return StringCell.TYPE;
        } else if (Integer.class.isAssignableFrom(binding) || Short.class.isAssignableFrom(binding)
            || Byte.class.isAssignableFrom(binding)) {
            return IntCell.TYPE;
        } else if (Long.class.isAssignableFrom(binding)) {
            return LongCell.TYPE;
        } else if (Double.class.isAssignableFrom(binding) || Float.class.isAssignableFrom(binding)) {
            return DoubleCell.TYPE;
        } else if (Boolean.class.isAssignableFrom(binding)) {
            return BooleanCell.TYPE;
        } else if (LocalDate.class.isAssignableFrom(binding)) {
            return LocalDateCellFactory.TYPE;
        } else if (LocalDateTime.class.isAssignableFrom(binding) || Date.class.isAssignableFrom(binding)) {
            return LocalDateTimeCellFactory.TYPE;
        }
        // fall back to the attribute's string representation rather than dropping the column
        return StringCell.TYPE;
    }

    /**
     * Converts one non-geometry attribute value read from a {@link SimpleFeature} into the matching KNIME
     * {@link DataCell}, using the same type dispatch as {@link #knimeTypeFor(Class)}.
     */
    public static DataCell toDataCell(final Object value) {
        if (value == null) {
            return DataType.getMissingCell();
        } else if (value instanceof String s) {
            return new StringCell(s);
        } else if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return new IntCell(((Number)value).intValue());
        } else if (value instanceof Long l) {
            return new LongCell(l);
        } else if (value instanceof Double || value instanceof Float) {
            return new DoubleCell(((Number)value).doubleValue());
        } else if (value instanceof Boolean b) {
            return BooleanCell.get(b);
        } else if (value instanceof LocalDateTime ldt) {
            return LocalDateTimeCellFactory.create(ldt);
        } else if (value instanceof LocalDate ld) {
            return LocalDateCellFactory.create(ld);
        } else if (value instanceof Date d) {
            return LocalDateTimeCellFactory.create(LocalDateTime.ofInstant(d.toInstant(), ZoneId.systemDefault()));
        }
        return new StringCell(value.toString());
    }

    /**
     * Converts a JTS {@link Geometry} plus its layer's coordinate reference system into a KNIME geometry
     * {@link DataCell}, via WKB + {@link GeoCellFactory} — the same construction path the Python extension's
     * Java-side type bridge ({@code org.knime.geospatial.python}'s {@code knime/types/geospatial.py}) targets, so
     * the resulting column is fully interoperable with the existing Python geo nodes.
     */
    public static DataCell toGeoCell(final Geometry geometry, final CoordinateReferenceSystem crs)
        throws KNIMEException {
        if (geometry == null) {
            return DataType.getMissingCell();
        }
        try {
            final byte[] wkb = new WKBWriter().write(geometry);
            final GeoReferenceSystem refSystem =
                crs == null ? GeoReferenceSystem.DEFAULT : GeoReferenceSystemFactory.create(CRS.toSRS(crs));
            return GeoCellFactory.create(wkb, refSystem);
        } catch (final IOException e) {
            throw KNIMEException.of(Message.fromSummary("Could not create geometry cell: " + e.getMessage()), e);
        }
    }

    /**
     * Reverse of {@link #toGeoCell}: parses a KNIME {@link GeoValue}'s WKB back into a JTS {@link Geometry} for
     * handing to a GeoTools {@code SimpleFeatureBuilder}.
     */
    public static Geometry toJtsGeometry(final GeoValue value) throws KNIMEException {
        try {
            return new WKBReader().read(value.getWKB());
        } catch (final ParseException e) {
            throw KNIMEException.of(Message.fromSummary("Could not parse geometry: " + e.getMessage()), e);
        }
    }

    /**
     * Determines the CRS to use for a geometry column being written out: the common CRS shared by all non-missing
     * {@link GeoValue}s, or WGS84 if every value in the column is missing. Mirrors the Python extension's
     * {@code ToGeoPandasColumnConverter}, which likewise skips missing values and raises rather than silently
     * picking a CRS when a column mixes more than one.
     *
     * @throws KNIMEException if the column contains values with more than one distinct CRS
     */
    public static CoordinateReferenceSystem findCrs(final BufferedDataTable table, final int geoColIdx)
        throws KNIMEException {
        String firstCrsString = null;
        CoordinateReferenceSystem crs = null;
        for (final DataRow row : table) {
            final DataCell cell = row.getCell(geoColIdx);
            if (cell.isMissing()) {
                continue;
            }
            final GeoValue value = (GeoValue)cell;
            final String crsString = value.getReferenceSystem() == null ? null : value.getReferenceSystem().getCRS();
            if (crs == null) {
                firstCrsString = crsString;
                crs = toCoordinateReferenceSystem(value);
            } else if (!java.util.Objects.equals(firstCrsString, crsString)) {
                throw KNIMEException.of(Message.fromSummary("Can only work with exactly one coordinate reference "
                    + "system in one column, but got at least two: '" + firstCrsString + "' and '" + crsString
                    + "'."));
            }
        }
        return crs == null ? org.geotools.referencing.crs.DefaultGeographicCRS.WGS84 : crs;
    }

    /**
     * Resolves a KNIME {@link GeoValue}'s CRS string (e.g. {@code "EPSG:4326"}) to a GeoTools
     * {@link CoordinateReferenceSystem}, falling back to WGS84 if the value carries no CRS or it cannot be decoded.
     */
    public static CoordinateReferenceSystem toCoordinateReferenceSystem(final GeoValue value) {
        final String crsString = value.getReferenceSystem() == null ? null : value.getReferenceSystem().getCRS();
        if (crsString == null || crsString.isBlank()) {
            return org.geotools.referencing.crs.DefaultGeographicCRS.WGS84;
        }
        try {
            return CRS.decode(crsString);
        } catch (final Exception e) { // NOSONAR - CRS.decode's checked exceptions plus malformed-string cases
            try {
                return CRS.parseWKT(crsString);
            } catch (final Exception e2) { // NOSONAR - genuinely falling back, any parse failure is equivalent here
                return org.geotools.referencing.crs.DefaultGeographicCRS.WGS84;
            }
        }
    }
}

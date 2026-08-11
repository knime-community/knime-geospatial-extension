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

/**
 * File-extension helpers mirroring the Python Geospatial Analytics Extension's
 * {@code knut.ensure_file_extension} and the format-detection branching in
 * {@code GeoFileReaderNode.execute} (knime_extension/src/nodes/io.py).
 */
public final class GeoFileNames {

    private GeoFileNames() {
        // utility class
    }

    /**
     * Appends {@code extension} (e.g. {@code ".shp"}) to {@code fileName} unless it already ends with it
     * (case-insensitively) — mirrors {@code knut.ensure_file_extension}.
     */
    public static String ensureExtension(final String fileName, final String extension) {
        return fileName.toLowerCase().endsWith(extension.toLowerCase()) ? fileName : (fileName + extension);
    }

    /** Detected input format for the GeoFile Reader, mirroring the extension branching in {@code io.py}. */
    public enum DetectedFormat {
            KML, KMZ, PARQUET, GENERIC
    }

    /**
     * Detects which read path to take, mirroring the exact branch order in {@code GeoFileReaderNode.execute}: KML,
     * then KMZ, then any of the four Parquet-with-compression-suffix variants, then a generic GeoTools dispatch for
     * everything else (Shapefile/zipped Shapefile/GeoPackage/GeoJSON/MapInfo TAB).
     */
    public static DetectedFormat detect(final String fileName) {
        final String lower = fileName.toLowerCase();
        if (lower.endsWith(".kml")) {
            return DetectedFormat.KML;
        } else if (lower.endsWith(".kmz")) {
            return DetectedFormat.KMZ;
        } else if (lower.endsWith(".parquet") || lower.endsWith(".parquet.br") || lower.endsWith(".parquet.gz")
            || lower.endsWith(".parquet.snappy")) {
            return DetectedFormat.PARQUET;
        }
        return DetectedFormat.GENERIC;
    }
}

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

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.knime.node.parameters.widget.choices.Label;

/**
 * Mirrors the Python Geospatial Analytics Extension's {@code _EncodingOptions} enum
 * (knime_extension/src/nodes/io.py) exactly, so the two implementations offer identical choices.
 */
public enum GeoFileEncoding {

    @Label(value = "Auto", description = "Automatically detect the encoding from common options.")
    AUTO,

    @Label(value = "UTF-8",
        description = "Unicode Transformation Format - 8 bit. Default encoding suitable for most modern GIS data "
            + "files.")
    UTF8,

    @Label(value = "GB18030", description = "Chinese National Standard encoding. More comprehensive than GBK.")
    GB18030,

    @Label(value = "GBK", description = "Chinese internal code specification. Common in Chinese GIS software.")
    GBK,

    @Label(value = "GB2312", description = "Basic Simplified Chinese character encoding.")
    GB2312,

    @Label(value = "ISO-8859-1", description = "Latin-1 encoding. Suitable for Western European languages.")
    LATIN1,

    @Label(value = "Windows-1252", description = "Windows Western European encoding. Common in Windows systems.")
    WINDOWS1252,

    @Label(value = "ASCII", description = "Basic ASCII encoding. Only for standard ASCII characters.")
    ASCII;

    /**
     * @return the {@link Charset} to use for this option, empty for {@link #AUTO} (caller should let the underlying
     *         GeoTools/DBF reader/writer auto-detect in that case).
     */
    public Optional<Charset> toCharset() {
        return switch (this) {
            case AUTO -> Optional.empty();
            case UTF8 -> Optional.of(StandardCharsets.UTF_8);
            case GB18030 -> Optional.of(Charset.forName("GB18030"));
            case GBK -> Optional.of(Charset.forName("GBK"));
            case GB2312 -> Optional.of(Charset.forName("GB2312"));
            case LATIN1 -> Optional.of(StandardCharsets.ISO_8859_1);
            case WINDOWS1252 -> Optional.of(Charset.forName("windows-1252"));
            case ASCII -> Optional.of(StandardCharsets.US_ASCII);
        };
    }
}

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
package org.knime.geospatial.io.util;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.knime.node.parameters.widget.choices.Label;

/**
 * Encoding choices for the GeoFile Reader/Writer nodes. Unlike the Python Geospatial Analytics Extension's
 * {@code _EncodingOptions} enum (knime_extension/src/nodes/io.py), this deliberately has no "Auto" option - the
 * user always picks an explicit charset, defaulting to UTF-8.
 */
public enum GeoFileEncoding {

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
     * @return the {@link Charset} to use for this option.
     */
    public Charset toCharset() {
        return switch (this) {
            case UTF8 -> StandardCharsets.UTF_8;
            case GB18030 -> Charset.forName("GB18030");
            case GBK -> Charset.forName("GBK");
            case GB2312 -> Charset.forName("GB2312");
            case LATIN1 -> StandardCharsets.ISO_8859_1;
            case WINDOWS1252 -> Charset.forName("windows-1252");
            case ASCII -> StandardCharsets.US_ASCII;
        };
    }
}

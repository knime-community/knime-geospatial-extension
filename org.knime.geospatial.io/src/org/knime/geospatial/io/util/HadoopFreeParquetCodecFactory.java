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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.apache.parquet.bytes.BytesInput;
import org.apache.parquet.compression.CompressionCodecFactory;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.xerial.snappy.Snappy;

import com.aayushatharva.brotli4j.Brotli4jLoader;
import com.aayushatharva.brotli4j.decoder.DirectDecompress;
import com.aayushatharva.brotli4j.encoder.Encoder;

/**
 * A {@link CompressionCodecFactory} for Parquet's {@code UNCOMPRESSED}/{@code GZIP}/{@code SNAPPY}/{@code BROTLI}
 * codecs that never touches Hadoop's own codec classes. Parquet's built-in {@code CodecFactory} reflectively loads
 * Hadoop compression codec implementations (e.g. {@code org.apache.hadoop.io.compress.SnappyCodec}) regardless of
 * which {@code Configuration}/{@code ParquetConfiguration} it is constructed with, which this bundle cannot rely on
 * since it deliberately vendors only Hadoop's dependency-free client API (for class resolution) and not a real
 * Hadoop runtime. This factory instead delegates directly to the same native/Java libraries already vendored for
 * other purposes (snappy-java, brotli4j) or the JDK's own {@code java.util.zip}.
 */
public final class HadoopFreeParquetCodecFactory implements CompressionCodecFactory {

    /** Shared singleton — this factory is stateless. */
    public static final HadoopFreeParquetCodecFactory INSTANCE = new HadoopFreeParquetCodecFactory();

    private HadoopFreeParquetCodecFactory() {
    }

    @Override
    public BytesInputCompressor getCompressor(final CompressionCodecName codec) {
        return new Compressor(codec);
    }

    @Override
    public BytesInputDecompressor getDecompressor(final CompressionCodecName codec) {
        return new Decompressor(codec);
    }

    @Override
    public void release() {
        // no pooled resources to release
    }

    private static final class Compressor implements BytesInputCompressor {
        private final CompressionCodecName m_codec;

        Compressor(final CompressionCodecName codec) {
            m_codec = codec;
        }

        @Override
        public BytesInput compress(final BytesInput bytes) throws IOException {
            final byte[] input = bytes.toByteArray();
            switch (m_codec) {
                case UNCOMPRESSED:
                    return BytesInput.from(input);
                case GZIP:
                    final var baos = new ByteArrayOutputStream();
                    try (var gzip = new GZIPOutputStream(baos)) {
                        gzip.write(input);
                    }
                    return BytesInput.from(baos.toByteArray());
                case SNAPPY:
                    return BytesInput.from(Snappy.compress(input));
                case BROTLI:
                    Brotli4jLoader.ensureAvailability();
                    return BytesInput.from(Encoder.compress(input));
                default:
                    throw new UnsupportedOperationException("Unsupported compression codec: " + m_codec);
            }
        }

        @Override
        public CompressionCodecName getCodecName() {
            return m_codec;
        }

        @Override
        public void release() {
            // nothing to release
        }
    }

    private static final class Decompressor implements BytesInputDecompressor {
        private final CompressionCodecName m_codec;

        Decompressor(final CompressionCodecName codec) {
            m_codec = codec;
        }

        @Override
        public BytesInput decompress(final BytesInput bytes, final int decompressedSize) throws IOException {
            final byte[] input = bytes.toByteArray();
            switch (m_codec) {
                case UNCOMPRESSED:
                    return BytesInput.from(input);
                case GZIP:
                    final var out = new ByteArrayOutputStream(decompressedSize);
                    try (var gzip = new GZIPInputStream(new ByteArrayInputStream(input))) {
                        gzip.transferTo(out);
                    }
                    return BytesInput.from(out.toByteArray());
                case SNAPPY:
                    return BytesInput.from(Snappy.uncompress(input));
                case BROTLI:
                    Brotli4jLoader.ensureAvailability();
                    final DirectDecompress result = DirectDecompress.decompress(input);
                    return BytesInput.from(result.getDecompressedData());
                default:
                    throw new UnsupportedOperationException("Unsupported compression codec: " + m_codec);
            }
        }

        @Override
        public void decompress(final ByteBuffer input, final int compressedSize, final ByteBuffer output,
            final int decompressedSize) throws IOException {
            final byte[] in = new byte[compressedSize];
            input.get(in);
            final byte[] out = decompress(BytesInput.from(in), decompressedSize).toByteArray();
            output.put(out);
        }

        @Override
        public void release() {
            // nothing to release
        }
    }
}

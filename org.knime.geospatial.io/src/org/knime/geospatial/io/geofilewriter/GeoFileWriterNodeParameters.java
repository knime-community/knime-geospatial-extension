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
package org.knime.geospatial.io.geofilewriter;

import java.util.Optional;

import org.knime.core.data.DataColumnSpec;
import org.knime.filehandling.core.connections.FSCategory;
import org.knime.filehandling.core.connections.FSLocation;
import org.knime.filehandling.core.connections.RelativeTo;
import org.knime.geospatial.core.data.GeoValue;
import org.knime.geospatial.io.util.GeoFileEncoding;
import org.knime.node.parameters.Advanced;
import org.knime.node.parameters.NodeParameters;
import org.knime.node.parameters.NodeParametersInput;
import org.knime.node.parameters.Widget;
import org.knime.node.parameters.legacy.updates.ColumnNameAutoGuessValueProvider;
import org.knime.node.parameters.migration.LoadDefaultsForAbsentFields;
import org.knime.node.parameters.updates.Effect;
import org.knime.node.parameters.updates.Effect.EffectType;
import org.knime.node.parameters.updates.EffectPredicate;
import org.knime.node.parameters.updates.EffectPredicateProvider;
import org.knime.node.parameters.updates.ParameterReference;
import org.knime.node.parameters.updates.ValueProvider;
import org.knime.node.parameters.updates.ValueReference;
import org.knime.node.parameters.widget.choices.ChoicesProvider;
import org.knime.node.parameters.widget.choices.Label;
import org.knime.node.parameters.widget.choices.ValueSwitchWidget;
import org.knime.node.parameters.widget.choices.util.ColumnSelectionUtil;
import org.knime.node.parameters.widget.choices.util.CompatibleColumnsProvider;
import org.knime.node.parameters.widget.file.FileSelection;
import org.knime.node.parameters.widget.file.FileWriterWidget;

/**
 * Node parameters for GeoFile Writer, 1:1 mirroring {@code GeoFileWriterNode} in the Python Geospatial Analytics
 * Extension's {@code knime_extension/src/nodes/io.py}.
 */
@LoadDefaultsForAbsentFields
final class GeoFileWriterNodeParameters implements NodeParameters {

    GeoFileWriterNodeParameters() {
        // no defaults when no input context is available
    }

    GeoFileWriterNodeParameters(final NodeParametersInput input) {
        ColumnSelectionUtil.getFirstCompatibleColumnOfFirstPort(input, GeoValue.class)
            .ifPresent(col -> m_geoColumn = col.getName());
    }

    /** Mirrors the Python node's {@code dataformat} StringParameter choices exactly. */
    enum GeoFileFormat {
            @Label(value = "Shapefile", description = "Write an ESRI Shapefile (.shp/.shx/.dbf/.prj/.cpg).")
            SHAPEFILE,
            @Label(value = "GeoJSON", description = "Write a GeoJSON (.geojson) file.")
            GEOJSON,
            @Label(value = "GeoParquet", description = "Write a GeoParquet (.parquet) file.")
            GEOPARQUET,
            @Label(value = "GML", description = "Write a Geography Markup Language (.gml) file.")
            GML
    }

    /** Mirrors the Python node's {@code Compression} enum exactly. */
    enum ParquetCompression {
            @Label(value = "None", description = "Does not use any compression at all.")
            NONE,
            @Label(value = "Brotli", description = "Successor to gzip with better compression.")
            BROTLI,
            @Label(value = "gzip", description = "Widely used and supported compression format.")
            GZIP,
            @Label(value = "Snappy",
                description = "Compression format aiming for very high speed and reasonable compression.")
            SNAPPY
    }

    /** Mirrors the Python node's {@code ExistingFile} enum exactly. */
    enum ExistingFile {
            @Label(value = "Fail",
                description = "Will issue an error during the node's execution (to prevent unintentional "
                    + "overwrite).")
            FAIL,
            @Label(value = "Overwrite", description = "Will replace any existing file.")
            OVERWRITE
    }

    interface FormatRef extends ParameterReference<GeoFileFormat> {
    }

    static final class IsGeoParquet implements EffectPredicateProvider {
        @Override
        public EffectPredicate init(final PredicateInitializer i) {
            return i.getEnum(FormatRef.class).isOneOf(GeoFileFormat.GEOPARQUET);
        }
    }

    @Widget(title = "Geometry column", description = "Select the geometry column for Geodata.")
    @ChoicesProvider(GeoColumnChoicesProvider.class)
    @ValueReference(GeoColumnRef.class)
    @ValueProvider(GeoColumnAutoGuessProvider.class)
    String m_geoColumn;

    interface GeoColumnRef extends ParameterReference<String> {
    }

    static final class GeoColumnChoicesProvider extends CompatibleColumnsProvider {
        protected GeoColumnChoicesProvider() {
            super(GeoValue.class);
        }
    }

    /**
     * Re-guesses the geometry column live whenever the input changes (e.g. a table gets (re)connected after the
     * node already exists) — the constructor above only covers the initial guess at settings-creation time, which
     * a later reconnect does not retrigger. Only fills the field when it is currently empty (never overrides a
     * value the user picked, or a previous guess), matching {@link ColumnNameAutoGuessValueProvider}'s own
     * {@code isEmpty} check.
     */
    static final class GeoColumnAutoGuessProvider extends ColumnNameAutoGuessValueProvider {
        protected GeoColumnAutoGuessProvider() {
            super(GeoColumnRef.class);
        }

        @Override
        protected Optional<DataColumnSpec> autoGuessColumn(final NodeParametersInput parametersInput) {
            return ColumnSelectionUtil.getFirstCompatibleColumnOfFirstPort(parametersInput, GeoValue.class);
        }
    }

    @Widget(title = "Output file", description = """
            The file to write the structures to. The file extension (.shp, .geojson, .parquet, or .gml) is appended
            automatically depending on the selected file format if not specified.\
            """)
    @FileWriterWidget
    FileSelection m_outputFile = new FileSelection(
        new FSLocation(FSCategory.RELATIVE, RelativeTo.WORKFLOW_DATA.getSettingsValue(), "output.shp"));

    @Widget(title = "If exists", description = "Specifies the action to take if the output file already exists.")
    @ValueSwitchWidget
    ExistingFile m_overwritePolicy = ExistingFile.FAIL;

    @Widget(title = "Output file format", description = "The file format to use.")
    @ValueSwitchWidget
    @ValueReference(FormatRef.class)
    GeoFileFormat m_format = GeoFileFormat.SHAPEFILE;

    @Widget(title = "File compression", description = "The name of the compression to use or none.")
    @ValueSwitchWidget
    @Effect(predicate = IsGeoParquet.class, type = EffectType.SHOW)
    ParquetCompression m_parquetCompression = ParquetCompression.NONE;

    @Widget(title = "Encoding", description = "Select the encoding for saving the data file.")
    @Advanced
    GeoFileEncoding m_encoding = GeoFileEncoding.AUTO;
}

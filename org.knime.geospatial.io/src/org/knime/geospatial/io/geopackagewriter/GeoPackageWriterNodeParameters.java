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
 * Node parameters for GeoPackage Writer, 1:1 mirroring {@code GeoPackageWriterNode} in the Python Geospatial
 * Analytics Extension's {@code knime_extension/src/nodes/io.py}.
 */
@LoadDefaultsForAbsentFields
final class GeoPackageWriterNodeParameters implements NodeParameters {

    GeoPackageWriterNodeParameters() {
        // no defaults when no input context is available
    }

    GeoPackageWriterNodeParameters(final NodeParametersInput input) {
        ColumnSelectionUtil.getFirstCompatibleColumnOfFirstPort(input, GeoValue.class)
            .ifPresent(col -> m_geoColumn = col.getName());
    }

    /**
     * Mirrors the Python node's {@code ExistingFile} enum. Note the exact semantics being replicated: this only
     * guards whole-<em>file</em> overwrite. If the file already exists, writing a layer into it (new or existing
     * layer name) always proceeds and silently replaces that one layer — matching the Python node's own documented
     * behavior ("If file and layer already exist, the layer will be overwritten without a warning!").
     */
    enum ExistingFile {
            @Label(value = "Fail",
                description = "Will issue an error during the node's execution (to prevent unintentional "
                    + "overwrite).")
            FAIL,
            @Label(value = "Overwrite", description = "Will replace any existing file.")
            OVERWRITE
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

    @Widget(title = "Output file path", description = "Select the file path for saving data.")
    @FileWriterWidget(fileExtension = "gpkg")
    FileSelection m_outputFile = new FileSelection(
        new FSLocation(FSCategory.RELATIVE, RelativeTo.WORKFLOW_DATA.getSettingsValue(), "output.gpkg"));

    @Widget(title = "Output layer name for writing",
        description = "The output layer name in the GeoPackage data.")
    String m_layer = "new";

    @Widget(title = "If exists", description = "Specifies the action to take if the output file already exists.")
    @ValueSwitchWidget
    ExistingFile m_overwritePolicy = ExistingFile.FAIL;

    @Widget(title = "Encoding", description = """
            Leave at Auto unless you have a specific reason not to: the GeoPackage specification mandates UTF-8 \
            for text, and Auto (UTF-8) is what every other GeoPackage tool (GDAL, QGIS, ArcGIS) expects - the \
            written file stays fully spec-compliant and portable. Selecting any other encoding writes text columns \
            using that encoding's raw bytes instead, which any other reader (including GDAL/QGIS/ArcGIS, or this \
            same node's Reader left on Auto) will show as garbled or fail to read - only this bundle's own \
            GeoPackage Reader (Java), set to the exact same non-default encoding, can read it back correctly.\
            """)
    @Advanced
    GeoFileEncoding m_encoding = GeoFileEncoding.AUTO;
}

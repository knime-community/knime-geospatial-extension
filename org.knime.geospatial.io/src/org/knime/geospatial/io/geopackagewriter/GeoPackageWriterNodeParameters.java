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
package org.knime.geospatial.io.geopackagewriter;

import org.knime.filehandling.core.connections.FSCategory;
import org.knime.filehandling.core.connections.FSLocation;
import org.knime.filehandling.core.connections.RelativeTo;
import org.knime.geospatial.core.data.GeoValue;
import org.knime.geospatial.io.util.GeoFileEncoding;
import org.knime.node.parameters.NodeParameters;
import org.knime.node.parameters.Widget;
import org.knime.node.parameters.migration.LoadDefaultsForAbsentFields;
import org.knime.node.parameters.widget.choices.ChoicesProvider;
import org.knime.node.parameters.widget.choices.Label;
import org.knime.node.parameters.widget.choices.ValueSwitchWidget;
import org.knime.node.parameters.widget.choices.util.CompatibleColumnsProvider;
import org.knime.node.parameters.widget.file.FileSelection;
import org.knime.node.parameters.widget.file.FileWriterWidget;

/**
 * Node parameters for GeoPackage Writer, 1:1 mirroring {@code GeoPackageWriterNode} in the Python Geospatial
 * Analytics Extension's {@code knime_extension/src/nodes/io.py}.
 */
@LoadDefaultsForAbsentFields
final class GeoPackageWriterNodeParameters implements NodeParameters {

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
    String m_geoColumn;

    static final class GeoColumnChoicesProvider extends CompatibleColumnsProvider {
        protected GeoColumnChoicesProvider() {
            super(GeoValue.class);
        }
    }

    @Widget(title = "Output file path", description = "Select the file path for saving data.")
    @FileWriterWidget(fileExtension = "gpkg")
    FileSelection m_outputFile = new FileSelection(
        new FSLocation(FSCategory.RELATIVE, RelativeTo.WORKFLOW_DATA.getSettingsValue(), "output.gpkg"));

    @Widget(title = "Output layer name for writing",
        description = "The output layer name in the GeoPackage data.")
    String m_layer = "new";

    @Widget(title = "Encoding", description = "Select the encoding for saving the data file.")
    GeoFileEncoding m_encoding = GeoFileEncoding.AUTO;

    @Widget(title = "If exists", description = "Specifies the action to take if the output file already exists.")
    @ValueSwitchWidget
    ExistingFile m_overwritePolicy = ExistingFile.FAIL;
}

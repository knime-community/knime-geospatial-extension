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
package org.knime.geospatial.io.geopackagereader;

import org.knime.filehandling.core.connections.FSCategory;
import org.knime.filehandling.core.connections.FSLocation;
import org.knime.filehandling.core.connections.RelativeTo;
import org.knime.geospatial.io.util.GeoFileEncoding;
import org.knime.node.parameters.Advanced;
import org.knime.node.parameters.NodeParameters;
import org.knime.node.parameters.Widget;
import org.knime.node.parameters.migration.LoadDefaultsForAbsentFields;
import org.knime.node.parameters.widget.file.FileReaderWidget;
import org.knime.node.parameters.widget.file.FileSelection;

/**
 * Node parameters for GeoPackage Reader, 1:1 mirroring {@code GeoPackageReaderNode} in the Python Geospatial
 * Analytics Extension's {@code knime_extension/src/nodes/io.py}.
 */
@LoadDefaultsForAbsentFields
final class GeoPackageReaderNodeParameters implements NodeParameters {

    @Widget(title = "Input file path",
        description = "Select the file path or directly enter a remote URL for reading the data.")
    @FileReaderWidget(fileExtensions = "gpkg")
    FileSelection m_inputFile = new FileSelection(
        new FSLocation(FSCategory.RELATIVE, RelativeTo.WORKFLOW_DATA.getSettingsValue(), "input.gpkg"));

    @Widget(title = "Input layer name or order for reading",
        description = "The layer name in the multiple-layer data.")
    String m_layer = "";

    @Widget(title = "Encoding", description = "Select the encoding for reading the data file.")
    @Advanced
    GeoFileEncoding m_encoding = GeoFileEncoding.AUTO;
}

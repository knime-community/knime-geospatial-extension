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
import org.knime.node.parameters.layout.After;
import org.knime.node.parameters.layout.Layout;
import org.knime.node.parameters.layout.Section;
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

    @Widget(title = "Output file format", description = "The file format to use.")
    @ValueSwitchWidget
    @ValueReference(FormatRef.class)
    GeoFileFormat m_format = GeoFileFormat.SHAPEFILE;

    @Widget(title = "File compression", description = "The name of the compression to use or none.")
    @ValueSwitchWidget
    @Effect(predicate = IsGeoParquet.class, type = EffectType.SHOW)
    ParquetCompression m_parquetCompression = ParquetCompression.NONE;

    @Widget(title = "If exists", description = "Specifies the action to take if the output file already exists.")
    @ValueSwitchWidget
    ExistingFile m_overwritePolicy = ExistingFile.FAIL;

    @Widget(title = "Encoding", description = "Select the encoding for saving the data file.")
    @Advanced
    GeoFileEncoding m_encoding = GeoFileEncoding.AUTO;
}

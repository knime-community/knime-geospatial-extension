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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;

import org.knime.core.util.FileUtil;
import org.knime.filehandling.core.connections.FSFiles;
import org.knime.filehandling.core.connections.FSFiles.LocalFileHandle;
import org.knime.filehandling.core.connections.FSPath;

/**
 * Shapefile (multi-file: .shp/.shx/.dbf/.prj/.cpg) and GeoPackage (a single SQLite file) both need a real local
 * {@code java.io.File}/{@code java.nio.file.Path} for the underlying GeoTools {@code DataStore}/JDBC driver APIs —
 * the same constraint already solved for KNIME's H2/SQLite DB connectors
 * ({@code FileDBConnectorHelper.resolveToLocalFile}) and the Tableau Writer node
 * ({@code TableauHyperWriterNodeModel3}'s local-staging/upload logic). This class provides that same
 * resolve-to-local / stage-then-upload shape, reused across the GeoFile and GeoPackage nodes.
 * <p>
 * Unlike the Tableau Writer's own upload helper (which has a known correctness issue when appending to an existing
 * remote file — it can silently discard the write), uploads here always go through
 * {@link FSFiles#newOutputStream(FSPath, OpenOption...)} + {@link Files#copy(Path, java.io.OutputStream)}, which is
 * unconditionally correct for both new and pre-existing remote destinations.
 */
public final class LocalFileStaging {

    private LocalFileStaging() {
        // utility class
    }

    /**
     * Resolves an existing remote/relative/local {@code path} to a real local file, downloading it first if
     * necessary. Caller must close the returned handle once done reading.
     */
    public static LocalFileHandle resolveExistingToLocalFile(final FSPath path) throws IOException {
        return FSFiles.toLocalFile(path);
    }

    /** Creates an empty local temp file (not yet on disk) suitable as a staging target for a single-file format. */
    public static Path createLocalStagingFile(final String prefix, final String suffix) throws IOException {
        final Path tempFile = FileUtil.createTempFile(prefix, suffix).toPath().toAbsolutePath();
        // FileUtil.createTempFile reserves the name by creating an empty file; some writers (e.g. the SQLite/GeoTools
        // GeoPackage driver) require the path to not exist yet, so remove the empty placeholder again.
        Files.delete(tempFile);
        return tempFile;
    }

    /** Creates an empty local temp directory suitable as a staging target for a multi-file format (Shapefile). */
    public static Path createLocalStagingDir(final String prefix) throws IOException {
        return Files.createTempDirectory(prefix);
    }

    /** Deletes a local staging file if it still exists; safe to call unconditionally in a {@code finally} block. */
    public static void deleteQuietly(final Path localPath) {
        try {
            Files.deleteIfExists(localPath);
        } catch (final IOException e) { // NOSONAR - best-effort cleanup, nothing meaningful to do if it fails
            // ignore - a leftover temp file is not worth failing the node execution over
        }
    }

    /** Deletes a local staging directory (and its contents) if it still exists; safe to call unconditionally. */
    public static void deleteDirectoryQuietly(final Path localDir) {
        if (localDir == null || !Files.isDirectory(localDir)) {
            return;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(localDir)) {
            for (final Path entry : entries) {
                deleteQuietly(entry);
            }
        } catch (final IOException e) { // NOSONAR - best-effort cleanup
            // ignore
        }
        deleteQuietly(localDir);
    }

    /**
     * Uploads a single locally-staged file to {@code destPath} (e.g. a GeoPackage/GeoParquet output), overwriting
     * whatever is there. No-op (besides the copy) if {@code destPath} is itself local — {@code FSFiles} handles
     * that transparently.
     */
    public static void uploadFileTo(final Path localFile, final FSPath destPath, final OpenOption... openOptions)
        throws IOException {
        try (var out = FSFiles.newOutputStream(destPath, openOptions)) {
            Files.copy(localFile, out);
        }
    }

    /**
     * Uploads every file in {@code localDir} (e.g. the .shp/.shx/.dbf/.prj/.cpg set produced by a local Shapefile
     * write) to sibling files next to {@code destPath}, i.e. same parent directory, same base name, each file's own
     * extension. Used for multi-file formats where GeoTools itself only ever writes to a real local directory.
     */
    public static void uploadDirectoryTo(final Path localDir, final FSPath destPath, final OpenOption... openOptions)
        throws IOException {
        final String destFileName = destPath.getFileName().toString();
        final int dot = destFileName.lastIndexOf('.');
        final String destBaseName = dot < 0 ? destFileName : destFileName.substring(0, dot);
        final FSPath destParent = (FSPath)destPath.getParent();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(localDir)) {
            for (final Path entry : entries) {
                final String entryName = entry.getFileName().toString();
                final int entryDot = entryName.lastIndexOf('.');
                final String entryExtension = entryDot < 0 ? "" : entryName.substring(entryDot);
                final FSPath siblingDest = (FSPath)destParent.resolve(destBaseName + entryExtension);
                uploadFileTo(entry, siblingDest, openOptions);
            }
        }
    }
}

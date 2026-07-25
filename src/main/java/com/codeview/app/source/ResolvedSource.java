package com.codeview.app.source;

import java.nio.file.Path;
import java.util.List;

/**
 * Result of resolving a raw input path (folder, zip, or single file) into a
 * concrete, walkable set of source files.
 *
 * @param effectiveRoot the directory relative paths are computed against —
 *                       the folder itself for DIRECTORY, the extraction
 *                       target for ZIP, or the parent directory for FILE
 * @param javaFiles      every .java file to index
 * @param type           which of the three input shapes this was
 * @param temporary      true if effectiveRoot is a temp directory CodeView
 *                       created (ZIP extraction) and may clean up later —
 *                       never true for DIRECTORY or FILE, since those point
 *                       directly at the caller's own files
 */
public record ResolvedSource(Path effectiveRoot, List<Path> javaFiles, SourceType type, boolean temporary) {
}

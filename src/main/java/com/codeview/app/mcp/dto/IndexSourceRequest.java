package com.codeview.app.mcp.dto;

/**
 * Body for POST /mcp/code/reindex_all. sourcePath is optional — if omitted,
 * the configured codeview.repo-root is used. If given, it can point at a
 * directory, a .zip archive, or a single .java file; SourceResolver decides
 * which based on what's actually there.
 */
public record IndexSourceRequest(String sourcePath) {
}

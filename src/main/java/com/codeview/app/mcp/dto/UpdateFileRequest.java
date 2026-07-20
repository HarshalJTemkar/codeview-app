package com.codeview.app.mcp.dto;

/**
 * Body for POST /mcp/code/update_file. filePath is relative to codeview.repo-root.
 *
 * Deliberately has no "patch" or "diff" field: per Architecture Plan §2, this
 * endpoint only ever means "re-read this file from disk and re-chunk it."
 * CodeView never applies a patch to source — accepting a diff field here
 * would silently reopen the write path the source doc's design allowed and
 * you asked to close.
 */
public record UpdateFileRequest(String filePath) {
}

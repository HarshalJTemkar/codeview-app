package com.codeview.app.model;

import java.util.List;

/**
 * Mirrors the chunk record schema from the source design doc exactly:
 * chunk_id, file_path, language, start_line/end_line, ast_node, name,
 * dependencies, tags, text, hash, last_modified.
 *
 * No `embeddings` field: dropped per the confirmed "no token / OKF instead
 * of RAG" decision — there is no vector store in this design.
 */
public record ChunkRecord(
        String chunkId,
        String filePath,
        String language,
        int startLine,
        int endLine,
        String astNode,
        String name,
        List<String> dependencies,
        List<String> tags,
        String text,
        String hash,
        String lastModified
) {
}

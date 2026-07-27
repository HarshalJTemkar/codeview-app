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

    /**
     * Starts a fluent builder. Exists because callers assembling a chunk
     * (JavaAstChunker in particular) were previously calling the 12-argument
     * canonical constructor positionally — easy to get a field out of order
     * without the compiler ever noticing. The builder trades that for named,
     * order-independent calls at each call site.
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder pattern (Effective Java item 2): fluent, named construction for a record with many fields. */
    public static final class Builder {
        private String chunkId;
        private String filePath;
        private String language;
        private int startLine;
        private int endLine;
        private String astNode;
        private String name;
        private List<String> dependencies = List.of();
        private List<String> tags = List.of();
        private String text;
        private String hash;
        private String lastModified;

        private Builder() {
        }

        public Builder chunkId(String chunkId) {
            this.chunkId = chunkId;
            return this;
        }

        public Builder filePath(String filePath) {
            this.filePath = filePath;
            return this;
        }

        public Builder language(String language) {
            this.language = language;
            return this;
        }

        public Builder lines(int startLine, int endLine) {
            this.startLine = startLine;
            this.endLine = endLine;
            return this;
        }

        public Builder astNode(String astNode) {
            this.astNode = astNode;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder dependencies(List<String> dependencies) {
            this.dependencies = dependencies;
            return this;
        }

        public Builder tags(List<String> tags) {
            this.tags = tags;
            return this;
        }

        public Builder text(String text) {
            this.text = text;
            return this;
        }

        public Builder hash(String hash) {
            this.hash = hash;
            return this;
        }

        public Builder lastModified(String lastModified) {
            this.lastModified = lastModified;
            return this;
        }

        /** Builds the immutable ChunkRecord from whatever's been set so far. */
        public ChunkRecord build() {
            return new ChunkRecord(chunkId, filePath, language, startLine, endLine,
                    astNode, name, dependencies, tags, text, hash, lastModified);
        }
    }
}

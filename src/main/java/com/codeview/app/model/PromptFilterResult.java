package com.codeview.app.model;

import java.util.List;

/**
 * Response shape for POST /mcp/code/prompt_filter (Architecture Plan §11).
 *
 * noMatch is explicit and true when structural match, keyword fallback, and
 * link-graph walk all return nothing — the confirmed behavior is to return
 * empty context plus this flag, never a guessed fallback like a project
 * overview. The calling application decides what to do next.
 */
public record PromptFilterResult(
        boolean noMatch,
        String matchStrategy,   // "structural" | "keyword" | "none"
        List<ChunkRecord> matchedChunks,
        List<ChunkRecord> linkedChunks // pulled in via the link-graph walk, step 4
) {
    public static PromptFilterResult empty() {
        return new PromptFilterResult(true, "none", List.of(), List.of());
    }
}

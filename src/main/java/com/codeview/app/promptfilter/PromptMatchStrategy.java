package com.codeview.app.promptfilter;

import com.codeview.app.model.ChunkRecord;

import java.util.List;

/**
 * Strategy interface for one step in the prompt-time context filter's chain
 * of responsibility (Architecture Plan §11). Each implementation gets a
 * chance to match the prompt against the known chunks; {@link
 * PromptFilterService} tries them in order and stops at the first one that
 * finds something, per the confirmed design ("structural match first,
 * keyword fallback, then link-graph walk").
 *
 * <p>Every implementation must be purely deterministic (string/graph
 * matching only) — no model call, no ranking score. That constraint is what
 * keeps this pipeline inside the zero-token requirement, and it's why this
 * is an explicit interface rather than a generic "scoring function": a
 * scoring interface would invite a future implementation to plug in a
 * ranking model without anything here stopping it.
 */
public interface PromptMatchStrategy {

    /** Short, stable label identifying which strategy produced a match — surfaced in PromptFilterResult.matchStrategy(). */
    String name();

    /** Attempts to match the prompt against the known chunks. Returns an empty list, never null, if nothing matched. */
    List<ChunkRecord> match(String prompt, List<ChunkRecord> allChunks);
}

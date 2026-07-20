package com.codeview.app.promptfilter;

import com.codeview.app.model.ChunkRecord;
import com.codeview.app.model.PromptFilterResult;
import com.codeview.app.okf.OkfReader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/**
 * Orchestrates the prompt-time context filter (Architecture Plan §11), the
 * feature you called out as most prominent:
 *
 *   1. Intercept the prompt (caller passes it in — CodeView doesn't need to
 *      sit inline on the network path to do this, per §11's design).
 *   2. Structural match — explicit symbol names/paths in the prompt.
 *   3. Keyword fallback — if step 2 finds nothing.
 *   4. Link-graph walk — one hop from whatever matched in 2 or 3.
 *   5. Assemble and return — the caller attaches this to the prompt before
 *      its own LLM call. CodeView never calls an LLM itself.
 *
 * No-match behavior (confirmed): if steps 2-4 all find nothing, return empty
 * context with noMatch=true — never a guessed fallback.
 */
@Service
public class PromptFilterService {

    private final OkfReader okfReader;
    private final StructuralMatcher structuralMatcher;
    private final KeywordMatcher keywordMatcher;
    private final LinkGraphWalker linkGraphWalker;

    public PromptFilterService(OkfReader okfReader, StructuralMatcher structuralMatcher,
                                KeywordMatcher keywordMatcher, LinkGraphWalker linkGraphWalker) {
        this.okfReader = okfReader;
        this.structuralMatcher = structuralMatcher;
        this.keywordMatcher = keywordMatcher;
        this.linkGraphWalker = linkGraphWalker;
    }

    public PromptFilterResult filter(String prompt) throws IOException {
        List<ChunkRecord> allChunks = okfReader.readAll();

        List<ChunkRecord> structuralMatches = structuralMatcher.match(prompt, allChunks);
        if (!structuralMatches.isEmpty()) {
            List<ChunkRecord> linked = linkGraphWalker.walkOneHop(structuralMatches, allChunks);
            return new PromptFilterResult(false, "structural", structuralMatches, linked);
        }

        List<ChunkRecord> keywordMatches = keywordMatcher.match(prompt, allChunks);
        if (!keywordMatches.isEmpty()) {
            List<ChunkRecord> linked = linkGraphWalker.walkOneHop(keywordMatches, allChunks);
            return new PromptFilterResult(false, "keyword", keywordMatches, linked);
        }

        return PromptFilterResult.empty();
    }
}

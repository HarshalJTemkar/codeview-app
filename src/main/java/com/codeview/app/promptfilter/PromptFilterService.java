package com.codeview.app.promptfilter;

import com.codeview.app.model.ChunkRecord;
import com.codeview.app.model.PromptFilterResult;
import com.codeview.app.okf.OkfReader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Orchestrates the prompt-time context filter (Architecture Plan §11), the
 * feature called out as CodeView's most prominent:
 *
 * <ol>
 *   <li>Intercept the prompt (caller passes it in — CodeView doesn't need to
 *       sit inline on the network path to do this).</li>
 *   <li>Run each {@link PromptMatchStrategy} in {@link #chain}, in order,
 *       stopping at the first one that finds something — a <b>chain of
 *       responsibility</b> over <b>strategy</b> objects. Currently: {@link
 *       StructuralMatcher} (explicit symbol names/paths) then {@link
 *       KeywordMatcher} (term matching, only reached if structural found
 *       nothing).</li>
 *   <li>{@link LinkGraphWalker} pulls in one hop of dependency-linked
 *       chunks from whatever the winning strategy matched.</li>
 *   <li>Assemble and return — the caller attaches this to the prompt before
 *       its own LLM call. CodeView never calls an LLM itself.</li>
 * </ol>
 *
 * <p>No-match behavior (confirmed): if every strategy in the chain finds
 * nothing, return empty context with {@code noMatch=true} — never a guessed
 * fallback.
 */
@Service
public class PromptFilterService {

    private final OkfReader okfReader;
    private final LinkGraphWalker linkGraphWalker;
    private final List<PromptMatchStrategy> chain;

    /**
     * Chain order is fixed here explicitly — structural before keyword —
     * rather than left to Spring's ambient bean-injection order, so adding a
     * third {@link PromptMatchStrategy} implementation later can't silently
     * reorder the chain by accident.
     */
    public PromptFilterService(OkfReader okfReader, StructuralMatcher structuralMatcher,
                                KeywordMatcher keywordMatcher, LinkGraphWalker linkGraphWalker) {
        this.okfReader = okfReader;
        this.linkGraphWalker = linkGraphWalker;
        this.chain = List.of(structuralMatcher, keywordMatcher);
    }

    /**
     * Runs the full pipeline for one prompt within one project and returns
     * the assembled (possibly empty) context.
     * @param project which project's OKF store to search for matches
     */
    public PromptFilterResult filter(String prompt, String project) throws IOException {
        List<ChunkRecord> allChunks = okfReader.readAll(project);

        return runChain(prompt, allChunks)
                .map(winner -> toResult(winner, allChunks))
                .orElseGet(PromptFilterResult::empty);
    }

    /**
     * Tries each strategy in {@link #chain} in order and returns the first
     * one that produces a non-empty match, paired with its own matches — or
     * empty if every strategy in the chain came up with nothing.
     */
    private Optional<StrategyOutcome> runChain(String prompt, List<ChunkRecord> allChunks) {
        return chain.stream()
                .map(strategy -> new StrategyOutcome(strategy, strategy.match(prompt, allChunks)))
                .filter(outcome -> !outcome.matches().isEmpty())
                .findFirst();
    }

    /** Builds the final result from a winning strategy's matches, adding the one-hop link-graph walk. */
    private PromptFilterResult toResult(StrategyOutcome outcome, List<ChunkRecord> allChunks) {
        List<ChunkRecord> linked = linkGraphWalker.walkOneHop(outcome.matches(), allChunks);
        return new PromptFilterResult(false, outcome.strategy().name(), outcome.matches(), linked);
    }

    /** Pairs a strategy with what it matched, so runChain can report which one won without a second lookup. */
    private record StrategyOutcome(PromptMatchStrategy strategy, List<ChunkRecord> matches) {
    }
}

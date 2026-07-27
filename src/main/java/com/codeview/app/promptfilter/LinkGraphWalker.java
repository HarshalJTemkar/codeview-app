package com.codeview.app.promptfilter;

import com.codeview.app.model.ChunkRecord;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Step 4 of the prompt-time context filter (Architecture Plan §11): from
 * whatever matched in the winning {@link PromptMatchStrategy}, follow the
 * chunk's dependency links (imports/calls, stored as OKF cross-links) to
 * pull in immediately related chunks, so the caller gets a neighborhood of
 * context rather than one isolated snippet. One hop only, by design — this
 * stays a bounded, deterministic graph walk, not an open-ended traversal.
 */
@Component
public class LinkGraphWalker {

    /**
     * Finds every chunk (outside the already-matched set) whose name or file
     * path resolves one of the matched chunks' declared dependencies.
     *
     * @param matched   chunks the winning strategy already matched — excluded
     *                  from the result even if they'd otherwise qualify
     * @param allChunks the full known chunk set to search for link targets
     */
    public List<ChunkRecord> walkOneHop(List<ChunkRecord> matched, List<ChunkRecord> allChunks) {
        Set<String> matchedNames = namesOf(matched);
        Set<String> dependencyTargets = dependencyTargetsOf(matched);

        return allChunks.stream()
                .filter(chunk -> !matchedNames.contains(chunk.name()))
                .filter(chunk -> resolvesAnyDependency(chunk, dependencyTargets))
                .toList();
    }

    /** Names of the already-matched chunks, so the walk never re-includes them as "linked". */
    private Set<String> namesOf(List<ChunkRecord> chunks) {
        return chunks.stream().map(ChunkRecord::name).collect(Collectors.toSet());
    }

    /** Every dependency string declared across the matched chunks, de-duplicated but order-preserving. */
    private Set<String> dependencyTargetsOf(List<ChunkRecord> chunks) {
        Set<String> targets = new LinkedHashSet<>();
        chunks.forEach(chunk -> targets.addAll(chunk.dependencies()));
        return targets;
    }

    /**
     * True if any dependency string plausibly refers to this chunk — either
     * the dependency's simple name matches the chunk's symbol name, or the
     * dependency's dotted package path shows up in the chunk's file path.
     * Same string-matching approach as {@link StructuralMatcher} and {@link
     * KeywordMatcher}: deterministic, no model call.
     */
    private boolean resolvesAnyDependency(ChunkRecord chunk, Set<String> dependencyTargets) {
        return dependencyTargets.stream().anyMatch(dependency ->
                dependency.endsWith(chunk.name())
                        || chunk.filePath().contains(dependency.replace('.', '/')));
    }
}

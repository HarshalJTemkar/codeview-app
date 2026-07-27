package com.codeview.app.promptfilter;

import com.codeview.app.model.ChunkRecord;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Step 3 of the prompt-time context filter (Architecture Plan §11): when
 * structural matching finds nothing, fall back to matching prompt terms
 * against OKF frontmatter fields (tags, name, file_path). Lower precision
 * than structural match, but covers prompts that don't name exact symbols.
 * Still purely deterministic term matching — no model call, no ranking score.
 *
 * <p>Second strategy in the {@link PromptMatchStrategy} chain of
 * responsibility run by {@link PromptFilterService} — only reached if
 * {@link StructuralMatcher} (the first strategy) found nothing.
 */
@Component
public class KeywordMatcher implements PromptMatchStrategy {

    private static final int MIN_TERM_LENGTH = 3;

    private static final Set<String> STOPWORDS = Set.of(
            "the", "a", "an", "is", "why", "how", "what", "does", "this", "that",
            "in", "on", "for", "to", "of", "and", "or", "with", "it", "code",
            "please", "can", "you", "fix", "check"
    );

    @Override
    public String name() {
        return "keyword";
    }

    @Override
    public List<ChunkRecord> match(String prompt, List<ChunkRecord> allChunks) {
        List<String> terms = extractSignificantTerms(prompt);
        return allChunks.stream()
                .filter(chunk -> containsAnyTerm(chunk, terms))
                .toList();
    }

    /** Lower-cases the prompt, splits on non-word characters, and drops stopwords/very-short tokens. */
    private List<String> extractSignificantTerms(String prompt) {
        return Arrays.stream(prompt.toLowerCase().split("\\W+"))
                .filter(term -> term.length() >= MIN_TERM_LENGTH && !STOPWORDS.contains(term))
                .collect(Collectors.toList());
    }

    /** True if any extracted term appears in the chunk's name, file path, or tags (concatenated, lower-cased). */
    private boolean containsAnyTerm(ChunkRecord chunk, List<String> terms) {
        String haystack = (chunk.name() + " " + chunk.filePath() + " " + String.join(" ", chunk.tags()))
                .toLowerCase();
        return terms.stream().anyMatch(haystack::contains);
    }
}

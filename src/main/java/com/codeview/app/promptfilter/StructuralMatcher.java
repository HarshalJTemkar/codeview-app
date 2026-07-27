package com.codeview.app.promptfilter;

import com.codeview.app.model.ChunkRecord;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Step 2 of the prompt-time context filter (Architecture Plan §11): parse the
 * prompt for explicit symbol references (function/class names, or file
 * paths) actually named in the text, and match them directly against known
 * chunk names/paths. Deterministic string matching only — no model call.
 *
 * <p>First strategy in the {@link PromptMatchStrategy} chain of
 * responsibility run by {@link PromptFilterService} — highest precision of
 * the three steps, so it goes first.
 */
@Component
public class StructuralMatcher implements PromptMatchStrategy {

    // Matches likely identifiers: camelCase/PascalCase words, snake_case, or dotted paths.
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*");
    private static final int MIN_IDENTIFIER_LENGTH = 4;

    @Override
    public String name() {
        return "structural";
    }

    @Override
    public List<ChunkRecord> match(String prompt, List<ChunkRecord> allChunks) {
        List<String> candidates = extractCandidateIdentifiers(prompt);
        return allChunks.stream()
                .filter(chunk -> matchesAnyCandidate(chunk, candidates))
                .toList();
    }

    /** True if any extracted identifier equals the chunk's symbol name, or appears in its file path. */
    private boolean matchesAnyCandidate(ChunkRecord chunk, List<String> candidates) {
        return candidates.stream().anyMatch(candidate ->
                candidate.equalsIgnoreCase(chunk.name())
                        || chunk.filePath().toLowerCase().contains(candidate.toLowerCase()));
    }

    /**
     * Pulls every identifier-shaped token out of the prompt, skipping very
     * short/common words (under {@value #MIN_IDENTIFIER_LENGTH} characters)
     * that would otherwise cause false structural hits against unrelated
     * short symbol names.
     */
    private List<String> extractCandidateIdentifiers(String prompt) {
        Matcher matcher = IDENTIFIER.matcher(prompt);
        return matcher.results()
                .map(MatchResult::group)
                .filter(token -> token.length() >= MIN_IDENTIFIER_LENGTH)
                .toList();
    }
}

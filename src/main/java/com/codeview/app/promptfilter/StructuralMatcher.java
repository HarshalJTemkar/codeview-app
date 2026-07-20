package com.codeview.app.promptfilter;

import com.codeview.app.model.ChunkRecord;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Step 2 of the prompt-time context filter (Architecture Plan §11): parse the
 * prompt for explicit symbol references (function/class names, or file
 * paths) actually named in the text, and match them directly against known
 * chunk names/paths. Deterministic string matching only — no model call.
 */
@Component
public class StructuralMatcher {

    // Matches likely identifiers: camelCase/PascalCase words, snake_case, or dotted paths.
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*");

    public List<ChunkRecord> match(String prompt, List<ChunkRecord> allChunks) {
        List<String> candidates = extractCandidateIdentifiers(prompt);
        List<ChunkRecord> matches = new ArrayList<>();

        for (ChunkRecord chunk : allChunks) {
            for (String candidate : candidates) {
                if (candidate.equalsIgnoreCase(chunk.name())
                        || chunk.filePath().toLowerCase().contains(candidate.toLowerCase())) {
                    matches.add(chunk);
                    break;
                }
            }
        }
        return matches;
    }

    private List<String> extractCandidateIdentifiers(String prompt) {
        List<String> identifiers = new ArrayList<>();
        Matcher m = IDENTIFIER.matcher(prompt);
        while (m.find()) {
            String token = m.group();
            // Skip very short/common words that would cause false structural hits.
            if (token.length() >= 4) {
                identifiers.add(token);
            }
        }
        return identifiers;
    }
}

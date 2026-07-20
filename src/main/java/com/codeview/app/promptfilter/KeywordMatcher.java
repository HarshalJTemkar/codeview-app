package com.codeview.app.promptfilter;

import com.codeview.app.model.ChunkRecord;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
 */
@Component
public class KeywordMatcher {

    private static final Set<String> STOPWORDS = Set.of(
            "the", "a", "an", "is", "why", "how", "what", "does", "this", "that",
            "in", "on", "for", "to", "of", "and", "or", "with", "it", "code",
            "please", "can", "you", "fix", "check"
    );

    public List<ChunkRecord> match(String prompt, List<ChunkRecord> allChunks) {
        List<String> terms = Arrays.stream(prompt.toLowerCase().split("\\W+"))
                .filter(t -> t.length() >= 3 && !STOPWORDS.contains(t))
                .collect(Collectors.toList());

        List<ChunkRecord> matches = new ArrayList<>();
        for (ChunkRecord chunk : allChunks) {
            String haystack = (chunk.name() + " " + chunk.filePath() + " " + String.join(" ", chunk.tags()))
                    .toLowerCase();
            for (String term : terms) {
                if (haystack.contains(term)) {
                    matches.add(chunk);
                    break;
                }
            }
        }
        return matches;
    }
}

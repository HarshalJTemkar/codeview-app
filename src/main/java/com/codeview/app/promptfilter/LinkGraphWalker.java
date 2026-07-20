package com.codeview.app.promptfilter;

import com.codeview.app.model.ChunkRecord;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Step 4 of the prompt-time context filter (Architecture Plan §11): from
 * whatever matched in steps 2/3, follow the chunk's dependency links
 * (imports/calls, stored as OKF cross-links) to pull in immediately related
 * chunks, so the LLM gets a neighborhood of context rather than one isolated
 * snippet. One hop only, by design — this stays a bounded, deterministic
 * graph walk, not an open-ended traversal.
 */
@Component
public class LinkGraphWalker {

    public List<ChunkRecord> walkOneHop(List<ChunkRecord> matched, List<ChunkRecord> allChunks) {
        Set<String> matchedNames = matched.stream().map(ChunkRecord::name).collect(java.util.stream.Collectors.toSet());
        Set<String> dependencyTargets = new LinkedHashSet<>();
        for (ChunkRecord chunk : matched) {
            dependencyTargets.addAll(chunk.dependencies());
        }

        List<ChunkRecord> linked = new ArrayList<>();
        for (ChunkRecord chunk : allChunks) {
            if (matchedNames.contains(chunk.name())) {
                continue; // already in the primary match set
            }
            for (String dep : dependencyTargets) {
                if (dep.endsWith(chunk.name()) || chunk.filePath().contains(dep.replace('.', '/'))) {
                    linked.add(chunk);
                    break;
                }
            }
        }
        return linked;
    }
}

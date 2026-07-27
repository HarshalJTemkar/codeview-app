package com.codeview.app.promptfilter;

import com.codeview.app.chunker.JavaAstChunker;
import com.codeview.app.hash.MerkleHasher;
import com.codeview.app.model.PromptFilterResult;
import com.codeview.app.okf.OkfReader;
import com.codeview.app.okf.OkfWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the chain of responsibility in PromptFilterService actually runs in
 * the confirmed order (structural before keyword) rather than just that each
 * PromptMatchStrategy works correctly in isolation. Each test builds its own
 * small, isolated OKF store so the chunk set for one scenario can't
 * accidentally satisfy a different scenario's assertion.
 */
class PromptFilterServiceTest {

    @TempDir
    Path tempDir;

    /** Indexes one Java source snippet into a fresh OKF store and returns a PromptFilterService wired to it. */
    private PromptFilterService indexAndBuildService(String subDir, String relativeJavaPath, String javaSource)
            throws Exception {
        String okfRoot = tempDir.resolve(subDir).toString();
        OkfWriter writer = new OkfWriter(okfRoot);
        OkfReader reader = new OkfReader(okfRoot);
        JavaAstChunker chunker = new JavaAstChunker(new MerkleHasher());

        Path file = tempDir.resolve(subDir + "-src").resolve(relativeJavaPath.replace('/', '_') + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, javaSource);

        for (var chunk : chunker.chunk(file, relativeJavaPath)) {
            writer.write(chunk, subDir);
        }

        return new PromptFilterService(reader, new StructuralMatcher(), new KeywordMatcher(), new LinkGraphWalker());
    }

    @Test
    void structuralMatchWinsWhenPromptNamesTheSymbolExplicitly() throws Exception {
        PromptFilterService service = indexAndBuildService("structural-case", "com/example/Stats.java", """
                package com.example;
                public class Stats {
                    public int computeStats() { return 42; }
                }
                """);

        PromptFilterResult result = service.filter("why is computeStats slow?", "structural-case");

        assertFalse(result.noMatch());
        assertEquals("structural", result.matchStrategy());
        assertTrue(result.matchedChunks().stream().anyMatch(c -> c.name().equals("computeStats")));
    }

    @Test
    void fallsBackToKeywordOnlyWhenStructuralFindsNothing() throws Exception {
        // Method/class/file names deliberately unrelated to the prompt's
        // words, so StructuralMatcher (name-equals or filePath-contains)
        // cannot match anything here — the only way this chunk can be found
        // is via its "has-loop" tag, which KeywordMatcher's haystack checks
        // and StructuralMatcher never does.
        PromptFilterService service = indexAndBuildService("keyword-case", "com/example/Calculator.java", """
                package com.example;
                public class Calculator {
                    public int processData() {
                        int total = 0;
                        for (int i = 0; i < 10; i++) { total += i; }
                        return total;
                    }
                }
                """);

        PromptFilterResult result = service.filter("why is this loop running slowly", "keyword-case");

        assertFalse(result.noMatch());
        assertEquals("keyword", result.matchStrategy());
    }

    @Test
    void returnsExplicitNoMatchWhenNeitherStrategyFindsAnything() throws Exception {
        PromptFilterService service = indexAndBuildService("no-match-case", "com/example/Calculator.java", """
                package com.example;
                public class Calculator {
                    public int processData() { return 0; }
                }
                """);

        PromptFilterResult result = service.filter("xyzzy plugh completely unrelated gibberish", "no-match-case");

        assertTrue(result.noMatch());
        assertEquals("none", result.matchStrategy());
        assertTrue(result.matchedChunks().isEmpty());
        assertTrue(result.linkedChunks().isEmpty());
    }
}

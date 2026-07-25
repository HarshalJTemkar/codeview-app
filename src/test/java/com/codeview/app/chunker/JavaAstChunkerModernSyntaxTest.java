package com.codeview.app.chunker;

import com.codeview.app.hash.MerkleHasher;
import com.codeview.app.model.ChunkRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for the bug reported after running CodeView against its
 * own source: JavaAstChunker previously used StaticJavaParser.parse(), whose
 * default language level (historically Java 8) rejects records, text
 * blocks, and pattern-matching instanceof — exactly what this project's own
 * source uses. Each test here parses one of those three constructs directly.
 */
class JavaAstChunkerModernSyntaxTest {

    @TempDir
    Path tempDir;

    private final JavaAstChunker chunker = new JavaAstChunker(new MerkleHasher());

    @Test
    void parsesRecordDeclarations() throws Exception {
        Path file = tempDir.resolve("PointHolder.java");
        Files.writeString(file, """
                package com.example;

                public class PointHolder {
                    public record Point(int x, int y) {
                        public int sum() {
                            return x + y;
                        }
                    }
                }
                """);

        List<ChunkRecord> chunks = chunker.chunk(file, "com/example/PointHolder.java");
        assertFalse(chunks.isEmpty(), "record declarations should parse without throwing");
    }

    @Test
    void parsesTextBlocks() throws Exception {
        Path file = tempDir.resolve("Greeter.java");
        Files.writeString(file, """
                package com.example;

                public class Greeter {
                    public String greeting() {
                        return \"""
                            Hello,
                            world.
                            \""";
                    }
                }
                """);

        List<ChunkRecord> chunks = chunker.chunk(file, "com/example/Greeter.java");
        assertEquals(2, chunks.size()); // class + method
    }

    @Test
    void parsesPatternMatchingInstanceof() throws Exception {
        Path file = tempDir.resolve("Describer.java");
        Files.writeString(file, """
                package com.example;

                public class Describer {
                    public String describe(Object o) {
                        if (o instanceof String s) {
                            return "string of length " + s.length();
                        }
                        return "unknown";
                    }
                }
                """);

        List<ChunkRecord> chunks = chunker.chunk(file, "com/example/Describer.java");
        assertEquals(2, chunks.size());
    }
}

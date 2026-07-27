package com.codeview.app;

import com.codeview.app.chunker.JavaAstChunker;
import com.codeview.app.hash.MerkleHasher;
import com.codeview.app.model.ChunkRecord;
import com.codeview.app.okf.OkfReader;
import com.codeview.app.okf.OkfWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the core chunk -> hash -> OKF-write -> OKF-read loop actually works,
 * not just compiles. This is the pipeline everything else in the app
 * (search, tree, prompt-filter) reads from.
 */
class ChunkerOkfPipelineTest {

    @TempDir
    Path tempDir;

    private JavaAstChunker chunker;
    private OkfWriter writer;
    private OkfReader reader;

    @BeforeEach
    void setUp() {
        MerkleHasher hasher = new MerkleHasher();
        chunker = new JavaAstChunker(hasher);
        String okfRoot = tempDir.resolve("okf-store").toString();
        writer = new OkfWriter(okfRoot);
        reader = new OkfReader(okfRoot);
    }

    @Test
    void chunksClassAndMethodsFromSampleFile() throws Exception {
        Path sampleFile = tempDir.resolve("MathUtils.java");
        Files.writeString(sampleFile, """
                package com.example.util;

                import java.util.List;

                public class MathUtils {
                    public int sum(List<Integer> numbers) {
                        int total = 0;
                        for (int n : numbers) {
                            total += n;
                        }
                        return total;
                    }
                }
                """);

        List<ChunkRecord> chunks = chunker.chunk(sampleFile, "com/example/util/MathUtils.java");

        // One chunk for the class, one for the method.
        assertEquals(2, chunks.size());
        assertTrue(chunks.stream().anyMatch(c -> c.astNode().equals("ClassOrInterfaceDeclaration")
                && c.name().equals("MathUtils")));
        assertTrue(chunks.stream().anyMatch(c -> c.astNode().equals("MethodDeclaration")
                && c.name().equals("sum")));

        // The sum() method contains a for-loop, so its static tag should reflect that.
        ChunkRecord sumChunk = chunks.stream()
                .filter(c -> c.name().equals("sum"))
                .findFirst()
                .orElseThrow();
        assertTrue(sumChunk.tags().contains("has-loop"));
    }

    @Test
    void writesAndReadsBackOkfConceptFiles() throws Exception {
        Path sampleFile = tempDir.resolve("Simple.java");
        Files.writeString(sampleFile, """
                package com.example;

                public class Simple {
                    public String greet() {
                        return "hello";
                    }
                }
                """);

        List<ChunkRecord> chunks = chunker.chunk(sampleFile, "com/example/Simple.java");
        for (ChunkRecord chunk : chunks) {
            writer.write(chunk, "test-project");
        }

        List<ChunkRecord> readBack = reader.readAll("test-project");
        assertEquals(chunks.size(), readBack.size());
        assertTrue(readBack.stream().anyMatch(c -> c.name().equals("greet")));
    }

    @Test
    void identicalContentProducesIdenticalHash() throws Exception {
        MerkleHasher hasher = new MerkleHasher();
        String hash1 = hasher.hashContent("public void foo() {}");
        String hash2 = hasher.hashContent("public void foo() {}");
        String hash3 = hasher.hashContent("public void bar() {}");

        assertEquals(hash1, hash2);
        assertNotEquals(hash1, hash3);
    }
}

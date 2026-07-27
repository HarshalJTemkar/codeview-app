package com.codeview.app.okf;

import com.codeview.app.model.ChunkRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Directly proves the bug this round of changes fixes: two different
 * projects' chunks must never mix, even when they'd otherwise collide (same
 * relative file path, same chunk_id by coincidence, etc). Tested at the
 * OkfWriter/OkfReader layer directly, since every higher-level scoping
 * (ParallelIndexer, TreeService, ...) is only as isolated as this layer is.
 */
class OkfProjectIsolationTest {

    @TempDir
    Path tempDir;

    private OkfWriter writer;
    private OkfReader reader;

    @BeforeEach
    void setUp() {
        String okfRoot = tempDir.resolve("okf-store").toString();
        writer = new OkfWriter(okfRoot);
        reader = new OkfReader(okfRoot);
    }

    @Test
    void chunksWrittenToDifferentProjectsDoNotAppearInEachOthersReads() throws Exception {
        ChunkRecord chunkForProjectA = sampleChunk("aaaa1111", "com/example/Foo.java", "Foo");
        ChunkRecord chunkForProjectB = sampleChunk("bbbb2222", "com/example/Foo.java", "Foo"); // same relative path on purpose

        writer.write(chunkForProjectA, "project-a");
        writer.write(chunkForProjectB, "project-b");

        List<ChunkRecord> projectAChunks = reader.readAll("project-a");
        List<ChunkRecord> projectBChunks = reader.readAll("project-b");

        assertEquals(1, projectAChunks.size());
        assertEquals(1, projectBChunks.size());
        assertEquals("aaaa1111", projectAChunks.get(0).chunkId());
        assertEquals("bbbb2222", projectBChunks.get(0).chunkId());
    }

    @Test
    void readingAnUnindexedProjectReturnsEmptyRatherThanOtherProjectsData() throws Exception {
        writer.write(sampleChunk("aaaa1111", "com/example/Foo.java", "Foo"), "project-a");

        List<ChunkRecord> neverIndexedProject = reader.readAll("project-never-indexed");

        assertTrue(neverIndexedProject.isEmpty());
    }

    @Test
    void listProjectsReturnsExactlyWhatWasWritten() throws Exception {
        writer.write(sampleChunk("aaaa1111", "Foo.java", "Foo"), "zebra-project");
        writer.write(sampleChunk("bbbb2222", "Bar.java", "Bar"), "alpha-project");

        List<String> projects = reader.listProjects();

        assertEquals(List.of("alpha-project", "zebra-project"), projects, "should be sorted alphabetically");
    }

    @Test
    void rejectsAProjectNameThatWouldEscapeTheOkfRoot() {
        // ProjectNameResolver would normally prevent this upstream, but
        // OkfWriter/OkfReader must not trust that and re-check independently.
        assertThrows(IllegalArgumentException.class,
                () -> writer.write(sampleChunk("aaaa1111", "Foo.java", "Foo"), "../../escape-attempt"));
    }

    private ChunkRecord sampleChunk(String chunkId, String filePath, String name) {
        return ChunkRecord.builder()
                .chunkId(chunkId)
                .filePath(filePath)
                .language("java")
                .lines(1, 5)
                .astNode("ClassOrInterfaceDeclaration")
                .name(name)
                .dependencies(List.of())
                .tags(List.of())
                .text("public class " + name + " {}")
                .hash(chunkId)
                .lastModified("2026-07-25T00:00:00Z")
                .build();
    }
}

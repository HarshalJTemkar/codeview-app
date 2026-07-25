package com.codeview.app.upload;

import com.codeview.app.chunker.JavaAstChunker;
import com.codeview.app.config.CodeViewProperties;
import com.codeview.app.hash.MerkleHasher;
import com.codeview.app.index.ParallelIndexer;
import com.codeview.app.model.IndexRunResult;
import com.codeview.app.okf.OkfWriter;
import com.codeview.app.source.SourceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class UploadServiceTest {

    @TempDir
    Path tempDir;

    private UploadService uploadService;

    @BeforeEach
    void setUp() {
        CodeViewProperties props = new CodeViewProperties();
        props.setOkfRoot(tempDir.resolve("okf-store").toString());
        props.setMaxRetries(2);

        MerkleHasher hasher = new MerkleHasher();
        JavaAstChunker chunker = new JavaAstChunker(hasher);
        OkfWriter okfWriter = new OkfWriter(props.getOkfRoot());
        SourceResolver sourceResolver = new SourceResolver();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        ParallelIndexer parallelIndexer = new ParallelIndexer(executor, chunker, okfWriter, props, sourceResolver);
        uploadService = new UploadService(parallelIndexer, sourceResolver);
    }

    @Test
    void indexesUploadedZip() throws Exception {
        byte[] zipBytes = buildZip();
        MockMultipartFile zipFile = new MockMultipartFile("file", "project.zip", "application/zip", zipBytes);

        IndexRunResult result = uploadService.indexUploadedZip(zipFile);

        assertEquals(1, result.getSucceededFiles().size());
        assertTrue(result.getChunksWritten() > 0);
        assertEquals(0, result.getFailedFiles().size());
    }

    @Test
    void indexesUploadedFolderPreservingRelativePaths() throws Exception {
        MockMultipartFile file1 = new MockMultipartFile("files",
                "com/example/util/MathUtils.java", "text/plain",
                "package com.example.util;\npublic class MathUtils { public int sum() { return 1; } }".getBytes());
        MockMultipartFile file2 = new MockMultipartFile("files",
                "README.md", "text/plain", "not java, should be ignored".getBytes());

        IndexRunResult result = uploadService.indexUploadedFolder(
                new org.springframework.web.multipart.MultipartFile[]{file1, file2});

        assertEquals(1, result.getSucceededFiles().size());
        assertEquals("com/example/util/MathUtils.java", result.getSucceededFiles().get(0));
        assertTrue(result.getChunksWritten() > 0);
    }

    @Test
    void rejectsZipSlipInUploadedFolderPaths() throws Exception {
        MockMultipartFile evil = new MockMultipartFile("files",
                "../../evil/Evil.java", "text/plain",
                "class Evil {}".getBytes());

        IndexRunResult result = uploadService.indexUploadedFolder(
                new org.springframework.web.multipart.MultipartFile[]{evil});

        // The unsafe entry is skipped, leaving nothing to index — reported as a failure,
        // not silently ignored, and nothing was written outside the temp dir.
        assertEquals(1, result.getFailedFiles().size());
    }

    private byte[] buildZip() throws Exception {
        var baos = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("com/example/Foo.java"));
            zos.write("package com.example;\npublic class Foo { public void bar() {} }".getBytes());
            zos.closeEntry();
        }
        return baos.toByteArray();
    }
}

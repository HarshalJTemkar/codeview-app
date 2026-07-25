package com.codeview.app.upload;

import com.codeview.app.index.ParallelIndexer;
import com.codeview.app.model.IndexRunResult;
import com.codeview.app.source.SourceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Handles the two upload shapes the UI's upload page offers:
 *   - a single .zip file
 *   - a folder (browser sends one MultipartFile per file, each carrying its
 *     relative path as the filename via the webkitdirectory picker — see
 *     static/js/upload.js for how that's constructed client-side)
 *
 * Both paths end up calling the exact same ParallelIndexer.indexSource()
 * used by POST /mcp/code/reindex_all — uploads are just another way of
 * pointing at a source, not a separate indexing code path.
 */
@Service
public class UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadService.class);

    private final ParallelIndexer parallelIndexer;
    private final SourceResolver sourceResolver;

    public UploadService(ParallelIndexer parallelIndexer, SourceResolver sourceResolver) {
        this.parallelIndexer = parallelIndexer;
        this.sourceResolver = sourceResolver;
    }

    public IndexRunResult indexUploadedZip(MultipartFile file) throws Exception {
        if (file.isEmpty()) {
            IndexRunResult result = new IndexRunResult();
            result.addFailure(file.getOriginalFilename(), "Uploaded file is empty", 1);
            return result;
        }

        Path tempZip = Files.createTempFile("codeview-upload-", ".zip");
        try {
            file.transferTo(tempZip);
            log.info("Uploaded zip {} saved to {}, indexing...", file.getOriginalFilename(), tempZip);
            // SourceResolver detects the .zip extension and extracts to its own temp
            // dir (cleaned up automatically after indexing) — this temp file is just
            // the upload landing spot and is cleaned up here regardless of outcome.
            return parallelIndexer.indexSource(tempZip.toString());
        } finally {
            Files.deleteIfExists(tempZip);
        }
    }

    public IndexRunResult indexUploadedFolder(MultipartFile[] files) throws Exception {
        IndexRunResult result = new IndexRunResult();
        if (files == null || files.length == 0) {
            result.addFailure("(folder upload)", "No files were included in the upload", 1);
            return result;
        }

        Path tempDir = Files.createTempDirectory("codeview-upload-folder-");
        try {
            int copied = reconstructFolder(files, tempDir);
            if (copied == 0) {
                result.addFailure("(folder upload)", "No .java files found in the uploaded folder", 1);
                return result;
            }
            log.info("Reconstructed {} uploaded .java file(s) under {}, indexing...", copied, tempDir);
            return parallelIndexer.indexSource(tempDir.toString());
        } finally {
            sourceResolver.deleteRecursively(tempDir);
        }
    }

    /**
     * Writes each uploaded file to tempDir at its original relative path,
     * skipping anything that isn't a .java file and guarding against a
     * malicious relative path escaping tempDir — the same zip-slip-style
     * protection SourceResolver applies to .zip entries, applied here to
     * upload filenames since they're just as untrusted an input.
     */
    private int reconstructFolder(MultipartFile[] files, Path tempDir) throws IOException {
        int copied = 0;
        for (MultipartFile file : files) {
            String relativePath = file.getOriginalFilename();
            if (relativePath == null || !relativePath.toLowerCase().endsWith(".java")) {
                continue;
            }

            Path target = tempDir.resolve(relativePath).normalize();
            if (!target.startsWith(tempDir)) {
                log.warn("Skipping uploaded file with unsafe path: {}", relativePath);
                continue;
            }

            Files.createDirectories(target.getParent());
            file.transferTo(target);
            copied++;
        }
        return copied;
    }
}

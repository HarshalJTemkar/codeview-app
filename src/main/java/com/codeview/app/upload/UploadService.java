package com.codeview.app.upload;

import com.codeview.app.index.ParallelIndexer;
import com.codeview.app.model.IndexRunResult;
import com.codeview.app.project.ProjectNameResolver;
import com.codeview.app.source.SourceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Handles the two upload shapes the UI's upload page offers:
 *   - a single .zip file
 *   - a folder (browser sends one MultipartFile per file, each carrying its
 *     relative path as the filename via the webkitdirectory picker — see
 *     static/js/upload.js for how that's constructed client-side)
 *
 * Both paths end up calling {@link ParallelIndexer#indexSourceWithProject},
 * the same indexing implementation {@code POST /mcp/code/reindex_all} uses —
 * uploads are just another way of pointing at a source, not a separate
 * indexing code path.
 *
 * <p><b>Project-scoped (this build):</b> the project name is derived from
 * the browser's *original* filename/folder name — never the server-side
 * temp path — via {@link ProjectNameResolver}, and passed explicitly to
 * {@code indexSourceWithProject} rather than letting it re-derive from the
 * meaningless temp path.
 */
@Service
public class UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadService.class);

    private final ParallelIndexer parallelIndexer;
    private final SourceResolver sourceResolver;
    private final ProjectNameResolver projectNameResolver;

    public UploadService(ParallelIndexer parallelIndexer, SourceResolver sourceResolver,
                          ProjectNameResolver projectNameResolver) {
        this.parallelIndexer = parallelIndexer;
        this.sourceResolver = sourceResolver;
        this.projectNameResolver = projectNameResolver;
    }

    /** Saves an uploaded .zip to a temp file, indexes it into a project named after the upload's original filename, then cleans up. */
    public IndexRunResult indexUploadedZip(MultipartFile file) throws Exception {
        if (file.isEmpty()) {
            return emptyUploadFailure(file.getOriginalFilename());
        }

        String project = projectNameResolver.fromUploadedZipFilename(file.getOriginalFilename());
        Path tempZip = Files.createTempFile("codeview-upload-", ".zip");
        try {
            file.transferTo(tempZip);
            log.info("Uploaded zip {} saved to {}, indexing into project '{}'...",
                    file.getOriginalFilename(), tempZip, project);
            return parallelIndexer.indexSourceWithProject(tempZip.toString(), project);
        } finally {
            Files.deleteIfExists(tempZip);
        }
    }

    /** Reconstructs an uploaded folder into a temp directory, indexes it into a project named after the folder's own name, then cleans up. */
    public IndexRunResult indexUploadedFolder(MultipartFile[] files) throws Exception {
        if (files == null || files.length == 0) {
            return emptyUploadFailure("(folder upload)");
        }

        String project = projectNameResolver.fromFolderUploadRelativePaths(originalFilenamesOf(files));
        Path tempDir = Files.createTempDirectory("codeview-upload-folder-");
        try {
            int copied = reconstructFolder(files, tempDir);
            if (copied == 0) {
                return noJavaFilesFailure(project);
            }
            log.info("Reconstructed {} uploaded .java file(s) under {}, indexing into project '{}'...",
                    copied, tempDir, project);
            return parallelIndexer.indexSourceWithProject(tempDir.toString(), project);
        } finally {
            sourceResolver.deleteRecursively(tempDir);
        }
    }

    private List<String> originalFilenamesOf(MultipartFile[] files) {
        return java.util.Arrays.stream(files).map(MultipartFile::getOriginalFilename).toList();
    }

    private IndexRunResult emptyUploadFailure(String originalFilename) {
        IndexRunResult result = new IndexRunResult();
        result.addFailure(originalFilename, "Uploaded file is empty", 1);
        return result;
    }

    private IndexRunResult noJavaFilesFailure(String project) {
        IndexRunResult result = new IndexRunResult();
        result.setProject(project);
        result.addFailure("(folder upload)", "No .java files found in the uploaded folder", 1);
        return result;
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

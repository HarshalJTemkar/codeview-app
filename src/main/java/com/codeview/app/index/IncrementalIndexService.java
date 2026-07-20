package com.codeview.app.index;

import com.codeview.app.chunker.JavaAstChunker;
import com.codeview.app.hash.MerkleHasher;
import com.codeview.app.model.ChunkRecord;
import com.codeview.app.model.IndexRunResult;
import com.codeview.app.okf.OkfWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Re-indexes a single file on a file-watch or VCS-hook trigger (Architecture
 * Plan §5's update workflow): compute the file's new content hash, compare
 * against the last known hash (FileHashStore), and only re-chunk if it
 * actually changed. Unchanged files are skipped entirely — this is the
 * "walking only changed branches" behavior from the source doc, applied at
 * file granularity.
 */
@Service
public class IncrementalIndexService {

    private static final Logger log = LoggerFactory.getLogger(IncrementalIndexService.class);

    private final JavaAstChunker chunker;
    private final OkfWriter okfWriter;
    private final MerkleHasher hasher;
    private final FileHashStore hashStore;

    public IncrementalIndexService(JavaAstChunker chunker, OkfWriter okfWriter,
                                    MerkleHasher hasher, FileHashStore hashStore) {
        this.chunker = chunker;
        this.okfWriter = okfWriter;
        this.hasher = hasher;
        this.hashStore = hashStore;
    }

    /**
     * @param repoRoot     root of the target repository (read-only)
     * @param file         absolute path of the changed file
     * @param relativePath path relative to repoRoot, used as the chunk's file_path
     */
    public IndexRunResult reindexFile(Path repoRoot, Path file, String relativePath) {
        IndexRunResult result = new IndexRunResult();
        try {
            String content = Files.readString(file);
            String newHash = hasher.hashContent(content);

            if (!hashStore.hasChanged(relativePath, newHash)) {
                log.debug("File {} unchanged (hash match) — skipping re-chunk.", relativePath);
                result.addSuccess(relativePath, 0);
                return result;
            }

            List<ChunkRecord> chunks = chunker.chunk(file, relativePath);
            for (ChunkRecord chunk : chunks) {
                okfWriter.write(chunk);
            }
            hashStore.update(relativePath, newHash);
            result.addSuccess(relativePath, chunks.size());
        } catch (Exception e) {
            log.warn("Incremental re-index failed for {}: {}", relativePath, e.getMessage());
            result.addFailure(relativePath, e.getMessage(), 1);
        }
        return result;
    }
}

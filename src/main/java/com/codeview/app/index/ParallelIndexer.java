package com.codeview.app.index;

import com.codeview.app.chunker.JavaAstChunker;
import com.codeview.app.config.CodeViewProperties;
import com.codeview.app.model.ChunkRecord;
import com.codeview.app.model.IndexRunResult;
import com.codeview.app.okf.OkfWriter;
import com.codeview.app.source.ResolvedSource;
import com.codeview.app.source.SourceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * First-time (full) parallel index: partitions files across a worker pool
 * (Architecture Plan §12). Each worker owns disjoint files, so no
 * coordination is needed during chunking; failures are isolated and retried
 * per-file without stopping other workers; writes are idempotent (hash-keyed
 * OKF file names), so a retried worker can safely re-write without
 * duplicating anything.
 *
 * The source location can be a directory, a .zip archive, or a single .java
 * file — SourceResolver normalizes all three into the same file list before
 * this class does anything else.
 */
@Component
public class ParallelIndexer {

    private static final Logger log = LoggerFactory.getLogger(ParallelIndexer.class);

    private final ExecutorService executor;
    private final JavaAstChunker chunker;
    private final OkfWriter okfWriter;
    private final CodeViewProperties props;
    private final SourceResolver sourceResolver;

    public ParallelIndexer(ExecutorService executor, JavaAstChunker chunker,
                            OkfWriter okfWriter, CodeViewProperties props,
                            SourceResolver sourceResolver) {
        this.executor = executor;
        this.chunker = chunker;
        this.okfWriter = okfWriter;
        this.props = props;
        this.sourceResolver = sourceResolver;
    }

    /** Indexes the configured default source (codeview.repo-root). */
    public IndexRunResult indexAll() throws Exception {
        return indexSource(props.getRepoRoot());
    }

    /**
     * Indexes an explicitly given source path — folder, .zip, or single
     * .java file — overriding the configured default for this one run.
     */
    public IndexRunResult indexSource(String rawSourcePath) throws Exception {
        IndexRunResult result = new IndexRunResult();

        ResolvedSource resolved;
        try {
            resolved = sourceResolver.resolve(rawSourcePath);
        } catch (IllegalArgumentException e) {
            log.warn("Cannot index {}: {}", rawSourcePath, e.getMessage());
            result.addFailure(rawSourcePath, e.getMessage(), 1);
            return result;
        }

        log.info("Indexing {} file(s) from {} (source type: {})",
                resolved.javaFiles().size(), rawSourcePath, resolved.type());

        List<Future<FileOutcome>> futures = resolved.javaFiles().stream()
                .map(file -> executor.submit((Callable<FileOutcome>) () ->
                        indexOneFileWithRetry(resolved.effectiveRoot(), file)))
                .toList();

        for (Future<FileOutcome> future : futures) {
            FileOutcome outcome = future.get();
            if (outcome.success()) {
                result.addSuccess(outcome.filePath(), outcome.chunkCount());
            } else {
                result.addFailure(outcome.filePath(), outcome.failureReason(), outcome.attempts());
            }
        }

        if (resolved.temporary()) {
            sourceResolver.cleanupIfTemporary(resolved);
        }

        return result;
    }

    private FileOutcome indexOneFileWithRetry(Path effectiveRoot, Path file) {
        String relativePath = effectiveRoot.relativize(file).toString().replace('\\', '/');
        int attempts = 0;
        Exception lastError = null;

        while (attempts < props.getMaxRetries()) {
            attempts++;
            try {
                List<ChunkRecord> chunks = chunker.chunk(file, relativePath);
                for (ChunkRecord chunk : chunks) {
                    okfWriter.write(chunk);
                }
                return FileOutcome.success(relativePath, chunks.size());
            } catch (Exception e) {
                lastError = e;
                log.warn("Chunking attempt {} failed for {}: {}", attempts, relativePath, e.getMessage());
            }
        }
        return FileOutcome.failure(relativePath,
                lastError != null ? lastError.getMessage() : "unknown error", attempts);
    }

    private record FileOutcome(String filePath, boolean success, int chunkCount,
                                String failureReason, int attempts) {
        static FileOutcome success(String filePath, int chunkCount) {
            return new FileOutcome(filePath, true, chunkCount, null, 1);
        }

        static FileOutcome failure(String filePath, String reason, int attempts) {
            return new FileOutcome(filePath, false, 0, reason, attempts);
        }
    }
}


package com.codeview.app.index;

import com.codeview.app.chunker.JavaAstChunker;
import com.codeview.app.config.CodeViewProperties;
import com.codeview.app.model.ChunkRecord;
import com.codeview.app.model.IndexRunResult;
import com.codeview.app.okf.OkfWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.Stream;

/**
 * First-time (full) parallel index: partitions files across a worker pool
 * (Architecture Plan §12). Each worker owns disjoint files, so no
 * coordination is needed during chunking; failures are isolated and retried
 * per-file without stopping other workers; writes are idempotent (hash-keyed
 * OKF file names), so a retried worker can safely re-write without
 * duplicating anything.
 */
@Component
public class ParallelIndexer {

    private static final Logger log = LoggerFactory.getLogger(ParallelIndexer.class);

    private final ExecutorService executor;
    private final JavaAstChunker chunker;
    private final OkfWriter okfWriter;
    private final CodeViewProperties props;

    public ParallelIndexer(ExecutorService executor, JavaAstChunker chunker,
                            OkfWriter okfWriter, CodeViewProperties props) {
        this.executor = executor;
        this.chunker = chunker;
        this.okfWriter = okfWriter;
        this.props = props;
    }

    public IndexRunResult indexAll() throws Exception {
        Path repoRoot = Path.of(props.getRepoRoot());
        IndexRunResult result = new IndexRunResult();

        if (!Files.isDirectory(repoRoot)) {
            log.warn("Repo root {} does not exist or is not a directory; nothing to index.", repoRoot);
            return result;
        }

        List<Path> javaFiles;
        try (Stream<Path> walk = Files.walk(repoRoot)) {
            javaFiles = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }

        List<Future<FileOutcome>> futures = javaFiles.stream()
                .map(file -> executor.submit((Callable<FileOutcome>) () -> indexOneFileWithRetry(repoRoot, file)))
                .toList();

        for (Future<FileOutcome> future : futures) {
            FileOutcome outcome = future.get();
            if (outcome.success()) {
                result.addSuccess(outcome.filePath(), outcome.chunkCount());
            } else {
                result.addFailure(outcome.filePath(), outcome.failureReason(), outcome.attempts());
            }
        }

        return result;
    }

    private FileOutcome indexOneFileWithRetry(Path repoRoot, Path file) {
        String relativePath = repoRoot.relativize(file).toString().replace('\\', '/');
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

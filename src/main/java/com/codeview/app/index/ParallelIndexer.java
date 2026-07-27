package com.codeview.app.index;

import com.codeview.app.chunker.JavaAstChunker;
import com.codeview.app.config.CodeViewProperties;
import com.codeview.app.model.ChunkRecord;
import com.codeview.app.model.IndexRunResult;
import com.codeview.app.okf.OkfWriter;
import com.codeview.app.project.ProjectNameResolver;
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
 * <p>The source location can be a directory, a .zip archive, or a single
 * .java file — {@link SourceResolver} normalizes all three into the same
 * file list before this class does anything else.
 *
 * <p><b>Project-scoped (this build):</b> every run indexes into one named
 * project's OKF subdirectory, keeping multiple indexed codebases from mixing
 * together. {@link #indexSource} derives the project name automatically from
 * the source path; {@link #indexSourceWithProject} lets a caller (e.g.
 * {@code UploadService}, which knows the browser's original filename rather
 * than a server-side temp path) supply the name explicitly instead.
 */
@Component
public class ParallelIndexer {

    private static final Logger log = LoggerFactory.getLogger(ParallelIndexer.class);

    private final ExecutorService executor;
    private final JavaAstChunker chunker;
    private final OkfWriter okfWriter;
    private final CodeViewProperties props;
    private final SourceResolver sourceResolver;
    private final ProjectNameResolver projectNameResolver;

    public ParallelIndexer(ExecutorService executor, JavaAstChunker chunker,
                            OkfWriter okfWriter, CodeViewProperties props,
                            SourceResolver sourceResolver, ProjectNameResolver projectNameResolver) {
        this.executor = executor;
        this.chunker = chunker;
        this.okfWriter = okfWriter;
        this.props = props;
        this.sourceResolver = sourceResolver;
        this.projectNameResolver = projectNameResolver;
    }

    /** Indexes the configured default source (codeview.repo-root), deriving its project name automatically. */
    public IndexRunResult indexAll() throws Exception {
        return indexSource(props.getRepoRoot());
    }

    /**
     * Indexes an explicitly given source path — folder, .zip, or single
     * .java file — deriving the project name from that path (last path
     * segment, `.zip` extension stripped).
     */
    public IndexRunResult indexSource(String rawSourcePath) throws Exception {
        String project = projectNameResolver.fromSourcePath(rawSourcePath);
        return indexSourceWithProject(rawSourcePath, project);
    }

    /**
     * Indexes a source path into an explicitly named project, bypassing
     * automatic name derivation — used when the caller already knows the
     * "real" name (e.g. the browser's original upload filename) and the
     * source path itself is just a server-side temp location that wouldn't
     * make a meaningful project name on its own.
     */
    public IndexRunResult indexSourceWithProject(String rawSourcePath, String project) throws Exception {
        ResolvedSource resolved = resolveOrRecordFailure(rawSourcePath, project);
        if (resolved == null) {
            IndexRunResult failed = new IndexRunResult();
            failed.setProject(project);
            failed.addFailure(rawSourcePath, "Could not resolve source", 1);
            return failed;
        }

        log.info("Indexing {} file(s) from {} into project '{}' (source type: {})",
                resolved.javaFiles().size(), rawSourcePath, project, resolved.type());

        IndexRunResult result = indexResolvedFiles(resolved, project);
        result.setProject(project);

        if (resolved.temporary()) {
            sourceResolver.cleanupIfTemporary(resolved);
        }
        return result;
    }

    /** Resolves the source path, returning null (rather than throwing) if resolution fails, so the caller can build a proper failure result. */
    private ResolvedSource resolveOrRecordFailure(String rawSourcePath, String project) {
        try {
            return sourceResolver.resolve(rawSourcePath);
        } catch (Exception e) {
            log.warn("Cannot index {} into project '{}': {}", rawSourcePath, project, e.getMessage());
            return null;
        }
    }

    /** Submits one chunking task per file to the worker pool and collects every outcome into one IndexRunResult. */
    private IndexRunResult indexResolvedFiles(ResolvedSource resolved, String project) throws Exception {
        IndexRunResult result = new IndexRunResult();

        List<Future<FileOutcome>> futures = resolved.javaFiles().stream()
                .map(file -> executor.submit((Callable<FileOutcome>) () ->
                        indexOneFileWithRetry(resolved.effectiveRoot(), file, project)))
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

    /** Chunks and writes one file, retrying up to codeview.max-retries times on failure before giving up on it. */
    private FileOutcome indexOneFileWithRetry(Path effectiveRoot, Path file, String project) {
        String relativePath = effectiveRoot.relativize(file).toString().replace('\\', '/');
        int attempts = 0;
        Exception lastError = null;

        while (attempts < props.getMaxRetries()) {
            attempts++;
            try {
                List<ChunkRecord> chunks = chunker.chunk(file, relativePath);
                for (ChunkRecord chunk : chunks) {
                    okfWriter.write(chunk, project);
                }
                return FileOutcome.success(relativePath, chunks.size());
            } catch (Exception e) {
                lastError = e;
                log.warn("Chunking attempt {} failed for {} (project '{}'): {}", attempts, relativePath, project, e.getMessage());
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

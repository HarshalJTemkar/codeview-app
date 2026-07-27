package com.codeview.app.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of a full (first-time) or incremental indexing run: how many files
 * succeeded, and which ones failed after exhausting retries. Per Architecture
 * Plan §12: failed files are reported, never silently dropped.
 *
 * <p><b>project (this build):</b> the project name this run indexed into —
 * every read endpoint (search/tree/flow/prompt_filter) needs this to know
 * which project's OKF store to read from, so it's returned here rather than
 * left for the caller to re-derive.
 */
public class IndexRunResult {
    private final List<String> succeededFiles = new ArrayList<>();
    private final List<FailedFile> failedFiles = new ArrayList<>();
    private int chunksWritten = 0;
    private String project;

    public void addSuccess(String filePath, int chunkCount) {
        succeededFiles.add(filePath);
        chunksWritten += chunkCount;
    }

    public void addFailure(String filePath, String reason, int attempts) {
        failedFiles.add(new FailedFile(filePath, reason, attempts));
    }

    public List<String> getSucceededFiles() {
        return succeededFiles;
    }

    public List<FailedFile> getFailedFiles() {
        return failedFiles;
    }

    public int getChunksWritten() {
        return chunksWritten;
    }

    public String getProject() {
        return project;
    }

    public void setProject(String project) {
        this.project = project;
    }

    public record FailedFile(String filePath, String reason, int attempts) {
    }
}

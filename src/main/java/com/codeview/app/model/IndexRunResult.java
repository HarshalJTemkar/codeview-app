package com.codeview.app.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of a full (first-time) or incremental indexing run: how many files
 * succeeded, and which ones failed after exhausting retries. Per Architecture
 * Plan §12: failed files are reported, never silently dropped.
 */
public class IndexRunResult {
    private final List<String> succeededFiles = new ArrayList<>();
    private final List<FailedFile> failedFiles = new ArrayList<>();
    private int chunksWritten = 0;

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

    public record FailedFile(String filePath, String reason, int attempts) {
    }
}

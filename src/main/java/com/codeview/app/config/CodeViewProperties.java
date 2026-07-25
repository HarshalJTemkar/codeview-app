package com.codeview.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from `codeview.*` in application.yml. Kept as one small class so every
 * configurable knob in the system is visible in one place.
 */
@ConfigurationProperties(prefix = "codeview")
public class CodeViewProperties {

    /**
     * Path to the target source. Accepts three shapes, resolved automatically
     * by SourceResolver: an existing directory, a .zip archive, or a single
     * .java file. Never written to — read-only in every case.
     */
    private String repoRoot = "./sample-repo";

    /** Absolute path to the OKF bundle output directory (markdown+YAML concept files). */
    private String okfRoot = "./okf-store";

    /**
     * Worker pool size for the first-time parallel index (Architecture Plan §12).
     * 0 means "scale to Runtime.getRuntime().availableProcessors()", which is the
     * confirmed default. Set explicitly to override/cap.
     */
    private int workerPoolSize = 0;

    /** Max retry attempts for a single file/worker before it's reported as failed. */
    private int maxRetries = 3;

    /** Whether the file watcher auto-triggers incremental re-index on save. */
    private boolean watchEnabled = true;

    public String getRepoRoot() {
        return repoRoot;
    }

    public void setRepoRoot(String repoRoot) {
        this.repoRoot = repoRoot;
    }

    public String getOkfRoot() {
        return okfRoot;
    }

    public void setOkfRoot(String okfRoot) {
        this.okfRoot = okfRoot;
    }

    public int getWorkerPoolSize() {
        return workerPoolSize;
    }

    public void setWorkerPoolSize(int workerPoolSize) {
        this.workerPoolSize = workerPoolSize;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public boolean isWatchEnabled() {
        return watchEnabled;
    }

    public void setWatchEnabled(boolean watchEnabled) {
        this.watchEnabled = watchEnabled;
    }
}

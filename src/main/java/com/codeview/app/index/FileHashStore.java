package com.codeview.app.index;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the last-known aggregate hash per file so re-index runs can compare
 * root/file hashes and skip unchanged files entirely (Architecture Plan §5,
 * the Merkle-diff mechanism). Persisted to disk so a restart doesn't force a
 * full re-chunk of the whole repo — this is also what makes crash recovery
 * idempotent (§10): on restart, comparison against these hashes shows most
 * files are unchanged.
 */
@Component
public class FileHashStore {

    private final Path storeFile;
    private final ConcurrentHashMap<String, String> hashes = new ConcurrentHashMap<>();

    public FileHashStore(@Value("${codeview.okf-root:./okf-store}") String okfRoot) {
        this.storeFile = Paths.get(okfRoot, ".file-hashes.properties");
        load();
    }

    public synchronized String get(String filePath) {
        return hashes.get(filePath);
    }

    public synchronized boolean hasChanged(String filePath, String newHash) {
        String existing = hashes.get(filePath);
        return existing == null || !existing.equals(newHash);
    }

    public synchronized void update(String filePath, String newHash) {
        hashes.put(filePath, newHash);
        persist();
    }

    private void load() {
        if (!Files.exists(storeFile)) {
            return;
        }
        Properties props = new Properties();
        try (var in = Files.newInputStream(storeFile)) {
            props.load(in);
            for (String key : props.stringPropertyNames()) {
                hashes.put(key, props.getProperty(key));
            }
        } catch (IOException e) {
            // Non-fatal: worst case is a full re-chunk on this restart, per §10 eventual consistency.
        }
    }

    private void persist() {
        Properties props = new Properties();
        props.putAll(hashes);
        try {
            Files.createDirectories(storeFile.getParent());
            try (var out = Files.newOutputStream(storeFile)) {
                props.store(out, "CodeView file hash store - do not edit by hand");
            }
        } catch (IOException e) {
            // Non-fatal per §10 (retry/backoff, eventual consistency) — logged by caller if needed.
        }
    }
}

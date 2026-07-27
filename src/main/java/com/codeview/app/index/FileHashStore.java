package com.codeview.app.index;

import com.codeview.app.project.ProjectStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the last-known aggregate hash per file, per project, so re-index
 * runs can compare hashes and skip unchanged files entirely (Architecture
 * Plan §5, the Merkle-diff mechanism). Persisted to disk (one properties
 * file per project) so a restart doesn't force a full re-chunk — this is
 * also what makes crash recovery idempotent (§10).
 *
 * <p><b>Project-scoped (this build):</b> hashes are keyed by (project,
 * relativePath), and persisted to {@code okfRoot/<project>/.file-hashes.properties}
 * — without this, two different projects that happen to share a relative
 * path (e.g. both have "com/example/Foo.java") would corrupt each other's
 * change-detection state.
 */
@Component
public class FileHashStore {

    private static final String KEY_SEPARATOR = "\u0000";

    private final Path okfRoot;
    private final ProjectStore projectStore;
    private final ConcurrentHashMap<String, String> hashes = new ConcurrentHashMap<>();
    private final Set<String> loadedProjects = ConcurrentHashMap.newKeySet();

    public FileHashStore(@Value("${codeview.okf-root:./okf-store}") String okfRoot, ProjectStore projectStore) {
        this.okfRoot = Paths.get(okfRoot);
        this.projectStore = projectStore;
    }

    public String get(String project, String filePath) {
        ensureLoaded(project);
        return hashes.get(compositeKey(project, filePath));
    }

    public boolean hasChanged(String project, String filePath, String newHash) {
        String existing = get(project, filePath);
        return existing == null || !existing.equals(newHash);
    }

    public void update(String project, String filePath, String newHash) {
        ensureLoaded(project);
        hashes.put(compositeKey(project, filePath), newHash);
        persist(project);
    }

    /** Loads a project's persisted hash file into memory the first time that project is touched this run. */
    private void ensureLoaded(String project) {
        if (!loadedProjects.add(project)) {
            return; // already loaded earlier this run
        }
        Path hashFile = projectHashFile(project);
        if (!Files.exists(hashFile)) {
            return;
        }
        Properties props = new Properties();
        try (var in = Files.newInputStream(hashFile)) {
            props.load(in);
            props.stringPropertyNames().forEach(key -> hashes.put(compositeKey(project, key), props.getProperty(key)));
        } catch (IOException e) {
            // Non-fatal: worst case is a full re-chunk for this project on this restart, per §10 eventual consistency.
        }
    }

    /** Persists only this project's entries (filtered by key prefix) to its own hash file — never mixes projects in one file. */
    private void persist(String project) {
        Properties props = new Properties();
        String prefix = project + KEY_SEPARATOR;
        hashes.forEach((key, value) -> {
            if (key.startsWith(prefix)) {
                props.setProperty(key.substring(prefix.length()), value);
            }
        });

        Path hashFile = projectHashFile(project);
        try {
            Files.createDirectories(hashFile.getParent());
            try (var out = Files.newOutputStream(hashFile)) {
                props.store(out, "CodeView file hash store for project '" + project + "' - do not edit by hand");
            }
        } catch (IOException e) {
            // Non-fatal per §10 (retry/backoff, eventual consistency).
        }
    }

    private Path projectHashFile(String project) {
        return projectStore.resolveProjectDir(okfRoot, project).resolve(".file-hashes.properties");
    }

    private String compositeKey(String project, String filePath) {
        return project + KEY_SEPARATOR + filePath;
    }
}

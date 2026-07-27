package com.codeview.app.project;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Resolves a project name to its subdirectory under the shared OKF root, and
 * lists which projects currently exist. This is the one place that turns
 * "project name" + "OKF root" into an actual filesystem path — every reader
 * and writer goes through it rather than concatenating paths themselves.
 */
@Component
public class ProjectStore {

    /**
     * Resolves {@code okfRoot/project}, guarding against a project name that
     * would escape okfRoot once resolved. {@link ProjectNameResolver}
     * should already prevent this ({@code sanitize} strips slashes and
     * leading dots), but this is a second, independent check — the same
     * defense-in-depth pattern already used for `.zip` extraction
     * (SourceResolver) and upload reconstruction (UploadService).
     */
    public Path resolveProjectDir(Path okfRoot, String project) {
        Path target = okfRoot.resolve(project).normalize();
        if (!target.startsWith(okfRoot.normalize())) {
            throw new IllegalArgumentException("Invalid project name (would escape the OKF root): " + project);
        }
        return target;
    }

    /** Lists every existing project's name (its subdirectory under okfRoot), sorted alphabetically. Empty if okfRoot doesn't exist yet. */
    public List<String> listProjects(Path okfRoot) throws IOException {
        if (!Files.isDirectory(okfRoot)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(okfRoot)) {
            return entries.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }
}

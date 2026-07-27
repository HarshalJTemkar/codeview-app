package com.codeview.app.project;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Derives the project-scoped subfolder name used to keep multiple indexed
 * projects from mixing together in one flat OKF store (the bug this package
 * exists to fix). Every derivation path ends in {@link #sanitize}, so the
 * result is always safe to use directly as a single path segment — no
 * slashes, no "..", nothing that could escape the OKF root when resolved.
 */
@Component
public class ProjectNameResolver {

    private static final String FALLBACK_PREFIX = "project-";
    private static final int MAX_NAME_LENGTH = 100;

    /**
     * For a directory or `.zip` path already on disk (used by {@code
     * codeview.repo-root} and the {@code reindex_all} sourcePath override):
     * the last path segment, with a trailing `.zip` stripped if present.
     */
    public String fromSourcePath(String rawPath) {
        Path path = Paths.get(rawPath);
        String fileName = path.getFileName() != null ? path.getFileName().toString() : rawPath;
        return sanitize(stripZipExtension(fileName));
    }

    /** For a `.zip` uploaded from the browser: the original filename the browser sent, not the server's temp filename. */
    public String fromUploadedZipFilename(String originalFilename) {
        String name = (originalFilename == null || originalFilename.isBlank()) ? "uploaded-zip" : originalFilename;
        return sanitize(stripZipExtension(name));
    }

    /**
     * For a folder uploaded via the browser's webkitdirectory picker: each
     * file's relative path is prefixed with the folder the user actually
     * selected (e.g. "myproject/src/Foo.java"), so the first path segment of
     * any uploaded file is the project name.
     */
    public String fromFolderUploadRelativePaths(List<String> relativePaths) {
        if (relativePaths == null || relativePaths.isEmpty()) {
            return sanitize("uploaded-folder");
        }
        String first = relativePaths.get(0);
        int slashIndex = first.indexOf('/');
        String topSegment = slashIndex > 0 ? first.substring(0, slashIndex) : "uploaded-folder";
        return sanitize(topSegment);
    }

    private String stripZipExtension(String name) {
        return name.toLowerCase().endsWith(".zip") ? name.substring(0, name.length() - 4) : name;
    }

    /**
     * Replaces anything that isn't alphanumeric/dot/dash/underscore with a
     * dash, strips leading dots (defense against a name built entirely of
     * ".." segments), caps length, and falls back to a timestamp-based name
     * if sanitizing leaves nothing usable.
     */
    private String sanitize(String raw) {
        String cleaned = raw.trim().replaceAll("[^a-zA-Z0-9._-]", "-").replaceAll("^[.]+", "");
        if (cleaned.isBlank()) {
            return FALLBACK_PREFIX + System.currentTimeMillis();
        }
        return cleaned.length() > MAX_NAME_LENGTH ? cleaned.substring(0, MAX_NAME_LENGTH) : cleaned;
    }
}

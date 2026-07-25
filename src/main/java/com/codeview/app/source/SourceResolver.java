package com.codeview.app.source;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Resolves the single `codeview.repo-root` (or a per-request override) into
 * a concrete list of .java files, regardless of whether it points at a
 * directory, a .zip archive, or one individual .java file. This is the one
 * place that branches on input shape — everything downstream (chunker,
 * indexer, watcher) just gets a List<Path> and an effectiveRoot to compute
 * relative paths against.
 *
 * Read-only guarantee, extended to this component: the original input
 * (directory, zip file, or single file) is only ever read. A .zip is
 * extracted into a fresh temp directory — the extracted copy is what
 * downstream components read; the source .zip itself is never modified.
 */
@Component
public class SourceResolver {

    private static final Logger log = LoggerFactory.getLogger(SourceResolver.class);

    public ResolvedSource resolve(String rawPath) throws IOException {
        Path input = Paths.get(rawPath);

        if (Files.isDirectory(input)) {
            return resolveDirectory(input);
        }
        if (Files.isRegularFile(input) && rawPath.toLowerCase().endsWith(".zip")) {
            return resolveZip(input);
        }
        if (Files.isRegularFile(input) && rawPath.toLowerCase().endsWith(".java")) {
            return resolveSingleFile(input);
        }
        throw new IllegalArgumentException(
                "Unsupported source path (must be an existing directory, .zip file, or .java file): " + rawPath);
    }

    private ResolvedSource resolveDirectory(Path dir) throws IOException {
        List<Path> javaFiles;
        try (Stream<Path> walk = Files.walk(dir)) {
            javaFiles = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        return new ResolvedSource(dir, javaFiles, SourceType.DIRECTORY, false);
    }

    private ResolvedSource resolveSingleFile(Path file) {
        return new ResolvedSource(file.getParent(), List.of(file), SourceType.FILE, false);
    }

    /**
     * Extracts the zip into a fresh temp directory, guarding against
     * zip-slip (entries whose path would resolve outside the extraction
     * target) — a basic safety check independent of the "no auth" decision,
     * since a malicious zip is a risk regardless of who can reach the API.
     */
    private ResolvedSource resolveZip(Path zipFile) throws IOException {
        Path tempDir = Files.createTempDirectory("codeview-zip-");
        log.info("Extracting {} to temp directory {}", zipFile, tempDir);

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path target = tempDir.resolve(entry.getName()).normalize();

                if (!target.startsWith(tempDir)) {
                    // zip-slip guard: refuse any entry that would escape the extraction dir.
                    log.warn("Skipping zip entry with unsafe path: {}", entry.getName());
                    continue;
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        List<Path> javaFiles;
        try (Stream<Path> walk = Files.walk(tempDir)) {
            javaFiles = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        return new ResolvedSource(tempDir, javaFiles, SourceType.ZIP, true);
    }

    /** Best-effort cleanup of a temp extraction directory created for a ZIP input. */
    public void cleanupIfTemporary(ResolvedSource source) {
        if (!source.temporary()) {
            return;
        }
        deleteRecursively(source.effectiveRoot());
    }

    /**
     * Deletes a directory and everything under it. Public so callers that
     * create their own temp directories outside SourceResolver (e.g. the
     * folder-upload endpoint reconstructing an uploaded tree) can reuse the
     * same safe cleanup instead of duplicating it.
     */
    public void deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.compareTo(a)) // delete children before parents
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            log.warn("Could not delete temp file {}: {}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("Could not clean up temp directory {}: {}", dir, e.getMessage());
        }
    }
}

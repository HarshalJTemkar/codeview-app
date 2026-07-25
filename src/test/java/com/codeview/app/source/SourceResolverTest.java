package com.codeview.app.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class SourceResolverTest {

    @TempDir
    Path tempDir;

    private final SourceResolver resolver = new SourceResolver();

    @Test
    void resolvesDirectoryInput() throws IOException {
        Path dir = tempDir.resolve("proj");
        Files.createDirectories(dir.resolve("com/example"));
        Files.writeString(dir.resolve("com/example/Foo.java"), "class Foo {}");
        Files.writeString(dir.resolve("README.md"), "not java"); // should be ignored

        ResolvedSource resolved = resolver.resolve(dir.toString());

        assertEquals(SourceType.DIRECTORY, resolved.type());
        assertFalse(resolved.temporary());
        assertEquals(1, resolved.javaFiles().size());
        assertTrue(resolved.javaFiles().get(0).toString().endsWith("Foo.java"));
    }

    @Test
    void resolvesSingleFileInput() throws IOException {
        Path file = tempDir.resolve("Bar.java");
        Files.writeString(file, "class Bar {}");

        ResolvedSource resolved = resolver.resolve(file.toString());

        assertEquals(SourceType.FILE, resolved.type());
        assertFalse(resolved.temporary());
        assertEquals(1, resolved.javaFiles().size());
        assertEquals(file, resolved.javaFiles().get(0));
        assertEquals(tempDir, resolved.effectiveRoot());
    }

    @Test
    void resolvesZipInput() throws IOException {
        Path zipFile = tempDir.resolve("project.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            zos.putNextEntry(new ZipEntry("com/example/Baz.java"));
            zos.write("class Baz {}".getBytes());
            zos.closeEntry();
        }

        ResolvedSource resolved = resolver.resolve(zipFile.toString());

        assertEquals(SourceType.ZIP, resolved.type());
        assertTrue(resolved.temporary());
        assertEquals(1, resolved.javaFiles().size());
        assertTrue(Files.exists(resolved.javaFiles().get(0)));

        // cleanup should remove the temp extraction dir entirely
        resolver.cleanupIfTemporary(resolved);
        assertFalse(Files.exists(resolved.effectiveRoot()));
    }

    @Test
    void rejectsZipSlipEntries() throws IOException {
        Path zipFile = tempDir.resolve("evil.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            // attempts to escape the extraction directory
            zos.putNextEntry(new ZipEntry("../../evil.java"));
            zos.write("class Evil {}".getBytes());
            zos.closeEntry();
            // a normal, safe entry alongside it
            zos.putNextEntry(new ZipEntry("Safe.java"));
            zos.write("class Safe {}".getBytes());
            zos.closeEntry();
        }

        ResolvedSource resolved = resolver.resolve(zipFile.toString());

        // the malicious entry must be skipped; the safe one still indexed
        assertEquals(1, resolved.javaFiles().size());
        assertTrue(resolved.javaFiles().get(0).toString().endsWith("Safe.java"));
        // and nothing was written outside the temp extraction dir
        assertFalse(Files.exists(tempDir.resolve("evil.java")));

        resolver.cleanupIfTemporary(resolved);
    }

    @Test
    void rejectsUnsupportedPath() {
        Path missing = tempDir.resolve("does-not-exist.txt");
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(missing.toString()));
    }
}

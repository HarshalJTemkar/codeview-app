package com.codeview.app.okf;

import com.codeview.app.model.ChunkRecord;
import com.codeview.app.project.ProjectStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes each chunk as one Open Knowledge Format (OKF) concept file: a
 * markdown file with a YAML frontmatter block for structured fields and a
 * body containing the chunk's source text. This is the mechanism that
 * replaces RAG/embeddings per Architecture Plan §7 — concepts are written
 * once at index time and read directly at query/prompt-filter time, with no
 * embedding model or vector index involved anywhere.
 *
 * <p><b>Project-scoped (this build):</b> every write goes under {@code
 * okfRoot/<project>/}, not the flat okfRoot directly — this is what keeps
 * multiple indexed projects from mixing their chunks together. See {@link
 * ProjectStore} for how the project subdirectory is resolved.
 *
 * <p>File naming is deterministic (by chunk_id) so re-generating an unchanged
 * chunk produces byte-identical output — this is what makes regeneration
 * idempotent under the Merkle-diff re-index in §5.
 */
@Component
public class OkfWriter {

    private final Path okfRoot;
    private final ProjectStore projectStore;

    @Autowired
    public OkfWriter(@Value("${codeview.okf-root:./okf-store}") String okfRoot, ProjectStore projectStore) {
        this.okfRoot = Paths.get(okfRoot);
        this.projectStore = projectStore;
    }

    /** Convenience constructor for tests/manual construction outside Spring, where a ProjectStore bean isn't wired. */
    public OkfWriter(String okfRoot) {
        this(okfRoot, new ProjectStore());
    }

    /** Writes one chunk's concept file under {@code okfRoot/<project>/<chunkId>.md}, creating the project directory if needed. */
    public Path write(ChunkRecord chunk, String project) throws IOException {
        Path projectDir = projectStore.resolveProjectDir(okfRoot, project);
        Files.createDirectories(projectDir);

        String content = renderConceptFile(chunk);
        Path outFile = projectDir.resolve(chunk.chunkId() + ".md");
        Files.writeString(outFile, content);
        return outFile;
    }

    /** Renders a chunk into its full OKF concept-file text: a YAML frontmatter block followed by a fenced code body. */
    private String renderConceptFile(ChunkRecord chunk) {
        Map<String, Object> frontmatter = buildFrontmatter(chunk);
        String yamlBlock = dumpYaml(frontmatter);
        return "---\n" + yamlBlock + "---\n\n```" + chunk.language() + "\n" + chunk.text() + "\n```\n";
    }

    /** Assembles the YAML frontmatter fields for one chunk, in the fixed field order the source schema specifies. */
    private Map<String, Object> buildFrontmatter(ChunkRecord chunk) {
        Map<String, Object> frontmatter = new LinkedHashMap<>();
        frontmatter.put("type", chunk.astNode());
        frontmatter.put("chunk_id", chunk.chunkId());
        frontmatter.put("file_path", chunk.filePath());
        frontmatter.put("language", chunk.language());
        frontmatter.put("name", chunk.name());
        frontmatter.put("start_line", chunk.startLine());
        frontmatter.put("end_line", chunk.endLine());
        frontmatter.put("tags", chunk.tags());
        frontmatter.put("dependencies", chunk.dependencies());
        frontmatter.put("hash", chunk.hash());
        frontmatter.put("last_modified", chunk.lastModified());
        return frontmatter;
    }

    private String dumpYaml(Map<String, Object> frontmatter) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        return new Yaml(options).dump(frontmatter);
    }

    public Path getOkfRoot() {
        return okfRoot;
    }
}

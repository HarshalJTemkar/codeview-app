package com.codeview.app.okf;

import com.codeview.app.model.ChunkRecord;
import com.codeview.app.project.ProjectStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads OKF concept files back into ChunkRecord objects. This is the entire
 * "retrieval" mechanism in this design — no embeddings, no vector search,
 * just parsing frontmatter and matching structured fields (Architecture Plan
 * §7 and §11).
 *
 * <p><b>Project-scoped (this build):</b> {@link #readAll} only ever reads
 * one project's subdirectory under the OKF root, never the whole root — see
 * {@link ProjectStore}. {@link #listProjects} is how a caller discovers what
 * project names actually exist, for a project picker or a default choice.
 */
@Component
public class OkfReader {

    private static final Pattern FRONTMATTER = Pattern.compile("^---\\n(.*?)\\n---\\n\\n```[a-zA-Z0-9]*\\n(.*)\\n```\\n?$",
            Pattern.DOTALL);

    private final Path okfRoot;
    private final ProjectStore projectStore;

    @Autowired
    public OkfReader(@Value("${codeview.okf-root:./okf-store}") String okfRoot, ProjectStore projectStore) {
        this.okfRoot = Paths.get(okfRoot);
        this.projectStore = projectStore;
    }

    /** Convenience constructor for tests/manual construction outside Spring, where a ProjectStore bean isn't wired. */
    public OkfReader(String okfRoot) {
        this(okfRoot, new ProjectStore());
    }

    /** Reads every chunk in one project's OKF store. Empty list if the project doesn't exist (not yet indexed). */
    public List<ChunkRecord> readAll(String project) throws IOException {
        Path projectDir = projectStore.resolveProjectDir(okfRoot, project);
        if (!Files.isDirectory(projectDir)) {
            return List.of();
        }

        List<ChunkRecord> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(projectDir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".md")).toList()) {
                readOne(f).ifPresent(result::add);
            }
        }
        return result;
    }

    /** Every project name currently indexed under the OKF root — for a UI project picker or a "pick a default" fallback. */
    public List<String> listProjects() throws IOException {
        return projectStore.listProjects(okfRoot);
    }

    /** Parses one concept file's frontmatter + body back into a ChunkRecord. Empty if the file doesn't match the expected shape. */
    @SuppressWarnings("unchecked")
    public Optional<ChunkRecord> readOne(Path file) throws IOException {
        String content = Files.readString(file);
        Matcher matcher = FRONTMATTER.matcher(content);
        if (!matcher.matches()) {
            return Optional.empty();
        }

        Map<String, Object> frontmatter = new Yaml().load(matcher.group(1));
        String body = matcher.group(2);

        List<String> tags = (List<String>) frontmatter.getOrDefault("tags", List.of());
        List<String> dependencies = (List<String>) frontmatter.getOrDefault("dependencies", List.of());

        return Optional.of(ChunkRecord.builder()
                .chunkId(String.valueOf(frontmatter.get("chunk_id")))
                .filePath(String.valueOf(frontmatter.get("file_path")))
                .language(String.valueOf(frontmatter.get("language")))
                .lines(asInt(frontmatter.get("start_line")), asInt(frontmatter.get("end_line")))
                .astNode(String.valueOf(frontmatter.get("type")))
                .name(String.valueOf(frontmatter.get("name")))
                .dependencies(dependencies)
                .tags(tags)
                .text(body)
                .hash(String.valueOf(frontmatter.get("hash")))
                .lastModified(String.valueOf(frontmatter.get("last_modified")))
                .build());
    }

    private int asInt(Object value) {
        if (value instanceof Integer i) {
            return i;
        }
        return value == null ? 0 : Integer.parseInt(value.toString());
    }
}

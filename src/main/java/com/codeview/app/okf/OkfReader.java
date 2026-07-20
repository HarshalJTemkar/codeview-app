package com.codeview.app.okf;

import com.codeview.app.model.ChunkRecord;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads OKF concept files back into ChunkRecord objects. This is the entire
 * "retrieval" mechanism in this design — no embeddings, no vector search,
 * just parsing frontmatter and matching structured fields (Architecture Plan
 * §7 and §11).
 */
@Component
public class OkfReader {

    private static final Pattern FRONTMATTER = Pattern.compile("^---\\n(.*?)\\n---\\n\\n```[a-zA-Z0-9]*\\n(.*)\\n```\\n?$",
            Pattern.DOTALL);

    private final Path okfRoot;

    @SuppressWarnings("unchecked")
    public OkfReader(@Value("${codeview.okf-root:./okf-store}") String okfRoot) {
        this.okfRoot = Paths.get(okfRoot);
    }

    public List<ChunkRecord> readAll() throws IOException {
        List<ChunkRecord> result = new ArrayList<>();
        if (!Files.isDirectory(okfRoot)) {
            return result;
        }
        try (Stream<Path> files = Files.list(okfRoot)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".md")).toList()) {
                readOne(f).ifPresent(result::add);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public java.util.Optional<ChunkRecord> readOne(Path file) throws IOException {
        String content = Files.readString(file);
        Matcher m = FRONTMATTER.matcher(content);
        if (!m.matches()) {
            return java.util.Optional.empty();
        }
        String yamlBlock = m.group(1);
        String body = m.group(2);

        Yaml yaml = new Yaml();
        Map<String, Object> fm = yaml.load(yamlBlock);

        List<String> tags = (List<String>) fm.getOrDefault("tags", List.of());
        List<String> deps = (List<String>) fm.getOrDefault("dependencies", List.of());

        ChunkRecord record = new ChunkRecord(
                String.valueOf(fm.get("chunk_id")),
                String.valueOf(fm.get("file_path")),
                String.valueOf(fm.get("language")),
                asInt(fm.get("start_line")),
                asInt(fm.get("end_line")),
                String.valueOf(fm.get("type")),
                String.valueOf(fm.get("name")),
                deps,
                tags,
                body,
                String.valueOf(fm.get("hash")),
                String.valueOf(fm.get("last_modified"))
        );
        return java.util.Optional.of(record);
    }

    private int asInt(Object o) {
        if (o instanceof Integer i) return i;
        if (o == null) return 0;
        return Integer.parseInt(o.toString());
    }
}

package com.codeview.app.okf;

import com.codeview.app.model.ChunkRecord;
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
 * File naming is deterministic (by chunk_id) so re-generating an unchanged
 * chunk produces byte-identical output — this is what makes regeneration
 * idempotent under the Merkle-diff re-index in §5.
 */
@Component
public class OkfWriter {

    private final Path okfRoot;

    public OkfWriter(@Value("${codeview.okf-root:./okf-store}") String okfRoot) {
        this.okfRoot = Paths.get(okfRoot);
    }

    public Path write(ChunkRecord chunk) throws IOException {
        Files.createDirectories(okfRoot);

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

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Yaml yaml = new Yaml(options);
        String yamlBlock = yaml.dump(frontmatter);

        String content = "---\n" + yamlBlock + "---\n\n```" + chunk.language() + "\n"
                + chunk.text() + "\n```\n";

        Path outFile = okfRoot.resolve(chunk.chunkId() + ".md");
        Files.writeString(outFile, content);
        return outFile;
    }

    public Path getOkfRoot() {
        return okfRoot;
    }
}

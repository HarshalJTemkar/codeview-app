package com.codeview.app.mcp;

import com.codeview.app.config.CodeViewProperties;
import com.codeview.app.index.IncrementalIndexService;
import com.codeview.app.index.ParallelIndexer;
import com.codeview.app.mcp.dto.IndexSourceRequest;
import com.codeview.app.mcp.dto.PromptFilterRequest;
import com.codeview.app.mcp.dto.SearchRequest;
import com.codeview.app.mcp.dto.UpdateFileRequest;
import com.codeview.app.model.ChunkRecord;
import com.codeview.app.model.IndexRunResult;
import com.codeview.app.model.PromptFilterResult;
import com.codeview.app.model.TreeNode;
import com.codeview.app.okf.OkfReader;
import com.codeview.app.project.ProjectNameResolver;
import com.codeview.app.promptfilter.PromptFilterService;
import com.codeview.app.tree.TreeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MCP-shaped REST API (Architecture Plan §9). No auth: no bearer-token check,
 * no RBAC, no OAuth — anywhere. This is intentional per your confirmed
 * requirement, not an omission. Whether that's safe depends on deployment
 * target, which is explicitly deferred (see docs/Architecture.md §13/§9).
 *
 * <p>"Plugged to any application": these are plain HTTP+JSON endpoints, so
 * any host that can make an HTTP call — editor plugin, agent, CI job,
 * script — can use this without CodeView knowing anything about that caller.
 *
 * <p><b>Project-scoped (this build):</b> multiple codebases can be indexed
 * side by side without mixing — every read endpoint below takes a required
 * {@code project} parameter naming which one to read from. {@link
 * #listProjects} is how a caller discovers what's actually been indexed.
 *
 * <p>Full interactive docs: /swagger-ui.html — generated from these
 * annotations, not maintained as a separate hand-written spec.
 */
@RestController
@RequestMapping("/mcp/code")
@Tag(name = "MCP code intelligence", description = "Structural search, chunk/tree access, indexing, and the prompt-time context filter — no auth, no LLM calls except inside prompt_filter's caller-side use of the returned context")
public class McpController {

    private final OkfReader okfReader;
    private final TreeService treeService;
    private final ParallelIndexer parallelIndexer;
    private final IncrementalIndexService incrementalIndexService;
    private final PromptFilterService promptFilterService;
    private final CodeViewProperties props;
    private final ProjectNameResolver projectNameResolver;

    public McpController(OkfReader okfReader, TreeService treeService, ParallelIndexer parallelIndexer,
                          IncrementalIndexService incrementalIndexService,
                          PromptFilterService promptFilterService, CodeViewProperties props,
                          ProjectNameResolver projectNameResolver) {
        this.okfReader = okfReader;
        this.treeService = treeService;
        this.parallelIndexer = parallelIndexer;
        this.incrementalIndexService = incrementalIndexService;
        this.promptFilterService = promptFilterService;
        this.props = props;
        this.projectNameResolver = projectNameResolver;
    }

    /** Every project name currently indexed — for a project picker, or to check a name before using it. */
    @Operation(summary = "List projects", description = "Every project subdirectory currently under the OKF root.")
    @GetMapping("/projects")
    public List<String> listProjects() throws Exception {
        return okfReader.listProjects();
    }

    /** Structural/keyword lookup over one project's OKF concept files. No ranking/embedding step. */
    @Operation(summary = "Search", description = "Matches query against chunk name, file path, and tags within one project. No ranking function, embedding, or model call — plain string matching, capped at topK results.")
    @PostMapping("/search")
    public Map<String, Object> search(@RequestBody SearchRequest request) throws Exception {
        List<ChunkRecord> all = okfReader.readAll(request.project());
        String query = request.query() == null ? "" : request.query().toLowerCase();
        int topK = request.topK() != null ? request.topK() : 5;

        List<ChunkRecord> matches = all.stream()
                .filter(c -> matchesQuery(c, query))
                .limit(topK)
                .toList();

        return Map.of(
                "status", "success",
                "project", request.project(),
                "data", matches.stream()
                        .map(c -> Map.of("chunk_id", c.chunkId(), "file", c.filePath(), "name", c.name()))
                        .collect(Collectors.toList())
        );
    }

    /** True if the query string appears in the chunk's name, file path, or any tag. */
    private boolean matchesQuery(ChunkRecord chunk, String query) {
        return chunk.name().toLowerCase().contains(query)
                || chunk.filePath().toLowerCase().contains(query)
                || chunk.tags().stream().anyMatch(tag -> tag.toLowerCase().contains(query));
    }

    /** Fetch a single chunk (OKF concept) by ID, within one project. */
    @Operation(summary = "Get chunk", description = "Fetches one chunk's full content (source text + metadata) by its chunk_id, within one project.")
    @GetMapping("/get_chunk/{chunkId}")
    public ChunkRecord getChunk(@PathVariable String chunkId, @RequestParam String project) throws Exception {
        return okfReader.readAll(project).stream()
                .filter(c -> c.chunkId().equals(chunkId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("No chunk found for id: " + chunkId + " in project: " + project));
    }

    /** Serialized tree/subtree for one project's lazy-loaded UI. */
    @Operation(summary = "Get tree", description = "Directory -> file -> class -> method tree for one project, derived from its current OKF concept files.")
    @GetMapping("/tree")
    public Map<String, TreeNode> tree(@RequestParam String project) throws Exception {
        return treeService.buildTree(project);
    }

    /**
     * Re-read and re-chunk one file from disk, within one project. Never
     * applies a patch — see the DTO's own doc comment for why that field
     * doesn't exist here.
     */
    @Operation(summary = "Update (re-index) one file", description = "Re-reads and re-chunks a single file from disk into one project. Never accepts or applies a patch/diff — read-only against the source in every case.")
    @PostMapping("/update_file")
    public IndexRunResult updateFile(@RequestBody UpdateFileRequest request) {
        Path repoRoot = Path.of(props.getRepoRoot());
        Path file = repoRoot.resolve(request.filePath());
        String project = request.project() != null ? request.project() : projectNameResolver.fromSourcePath(props.getRepoRoot());
        return incrementalIndexService.reindexFile(repoRoot, file, request.filePath(), project);
    }

    /**
     * Kicks off a full parallel index (Architecture Plan §12). Body is
     * optional: with no body (or an empty JSON object), indexes
     * codeview.repo-root. With {"sourcePath": "..."}, indexes that path
     * instead — a directory, a .zip archive, or a single .java file. The
     * project name is always derived automatically from the source path;
     * use the upload endpoints if you need to name it explicitly.
     */
    @Operation(summary = "Full re-index", description = "Parallel worker-pool index of a directory, .zip archive, or single .java file, into a project named after the source path. Omit the body to use the configured codeview.repo-root. The response's `project` field is what subsequent reads should use.")
    @PostMapping("/reindex_all")
    public IndexRunResult reindexAll(@RequestBody(required = false) IndexSourceRequest request) throws Exception {
        String sourcePath = (request != null && request.sourcePath() != null)
                ? request.sourcePath()
                : props.getRepoRoot();
        return parallelIndexer.indexSource(sourcePath);
    }

    /**
     * The prompt-time context filter — Architecture Plan §11, the most
     * prominent feature. Takes raw prompt text and a project name, returns
     * matched context for the caller to attach before its own LLM call.
     * CodeView does not call an LLM here or anywhere else in this endpoint.
     */
    @Operation(summary = "Prompt-time context filter", description = "Structural match -> keyword fallback -> one-hop link-graph walk, within one project. Returns matched/linked chunks, or noMatch:true with empty arrays. This endpoint never calls an LLM — the caller attaches the result to its own prompt.")
    @PostMapping("/prompt_filter")
    public PromptFilterResult promptFilter(@RequestBody PromptFilterRequest request) throws Exception {
        return promptFilterService.filter(request.prompt(), request.project());
    }

    private static class NoSuchElementException extends RuntimeException {
        NoSuchElementException(String message) {
            super(message);
        }
    }
}

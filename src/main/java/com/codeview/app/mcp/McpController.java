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
 * "Plugged to any application": these are plain HTTP+JSON endpoints, so any
 * host that can make an HTTP call — editor plugin, agent, CI job, script —
 * can use this without CodeView knowing anything about that caller.
 *
 * Full interactive docs: /swagger-ui.html — generated from these annotations,
 * not maintained as a separate hand-written spec.
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

    public McpController(OkfReader okfReader, TreeService treeService, ParallelIndexer parallelIndexer,
                          IncrementalIndexService incrementalIndexService,
                          PromptFilterService promptFilterService, CodeViewProperties props) {
        this.okfReader = okfReader;
        this.treeService = treeService;
        this.parallelIndexer = parallelIndexer;
        this.incrementalIndexService = incrementalIndexService;
        this.promptFilterService = promptFilterService;
        this.props = props;
    }

    /** Structural/keyword lookup over OKF concept files. No ranking/embedding step. */
    @Operation(summary = "Search", description = "Matches query against chunk name, file path, and tags. No ranking function, embedding, or model call — plain string matching, capped at topK results.")
    @PostMapping("/search")
    public Map<String, Object> search(@RequestBody SearchRequest request) throws Exception {
        List<ChunkRecord> all = okfReader.readAll();
        String query = request.query() == null ? "" : request.query().toLowerCase();
        int topK = request.topK() != null ? request.topK() : 5;

        List<ChunkRecord> matches = all.stream()
                .filter(c -> c.name().toLowerCase().contains(query)
                        || c.filePath().toLowerCase().contains(query)
                        || c.tags().stream().anyMatch(t -> t.toLowerCase().contains(query)))
                .limit(topK)
                .toList();

        return Map.of(
                "status", "success",
                "data", matches.stream()
                        .map(c -> Map.of("chunk_id", c.chunkId(), "file", c.filePath(), "name", c.name()))
                        .collect(Collectors.toList())
        );
    }

    /** Fetch a single chunk (OKF concept) by ID. */
    @Operation(summary = "Get chunk", description = "Fetches one chunk's full content (source text + metadata) by its chunk_id.")
    @GetMapping("/get_chunk/{chunkId}")
    public ChunkRecord getChunk(@PathVariable String chunkId) throws Exception {
        return okfReader.readAll().stream()
                .filter(c -> c.chunkId().equals(chunkId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("No chunk found for id: " + chunkId));
    }

    /** Serialized tree/subtree for the lazy-loaded UI. */
    @Operation(summary = "Get tree", description = "Directory -> file -> class -> method tree, derived from the current OKF concept files.")
    @GetMapping("/tree")
    public Map<String, TreeNode> tree() throws Exception {
        return treeService.buildTree();
    }

    /**
     * Re-read and re-chunk one file from disk. Never applies a patch — see
     * the DTO's own doc comment for why that field doesn't exist here.
     */
    @Operation(summary = "Update (re-index) one file", description = "Re-reads and re-chunks a single file from disk. Never accepts or applies a patch/diff — read-only against the source in every case.")
    @PostMapping("/update_file")
    public IndexRunResult updateFile(@RequestBody UpdateFileRequest request) {
        Path repoRoot = Path.of(props.getRepoRoot());
        Path file = repoRoot.resolve(request.filePath());
        return incrementalIndexService.reindexFile(repoRoot, file, request.filePath());
    }

    /**
     * Kicks off a full parallel index (Architecture Plan §12). Body is
     * optional: with no body (or an empty JSON object), indexes
     * codeview.repo-root. With {"sourcePath": "..."}, indexes that path
     * instead — a directory, a .zip archive, or a single .java file.
     */
    @Operation(summary = "Full re-index", description = "Parallel worker-pool index of a directory, .zip archive, or single .java file. Omit the body to use the configured codeview.repo-root.")
    @PostMapping("/reindex_all")
    public IndexRunResult reindexAll(@RequestBody(required = false) IndexSourceRequest request) throws Exception {
        String sourcePath = (request != null && request.sourcePath() != null)
                ? request.sourcePath()
                : props.getRepoRoot();
        return parallelIndexer.indexSource(sourcePath);
    }

    /**
     * The prompt-time context filter — Architecture Plan §11, the most
     * prominent feature. Takes raw prompt text, returns matched context for
     * the caller to attach before its own LLM call. CodeView does not call
     * an LLM here or anywhere else in this endpoint.
     */
    @Operation(summary = "Prompt-time context filter", description = "Structural match -> keyword fallback -> one-hop link-graph walk. Returns matched/linked chunks, or noMatch:true with empty arrays. This endpoint never calls an LLM — the caller attaches the result to its own prompt.")
    @PostMapping("/prompt_filter")
    public PromptFilterResult promptFilter(@RequestBody PromptFilterRequest request) throws Exception {
        return promptFilterService.filter(request.prompt());
    }

    private static class NoSuchElementException extends RuntimeException {
        NoSuchElementException(String message) {
            super(message);
        }
    }
}

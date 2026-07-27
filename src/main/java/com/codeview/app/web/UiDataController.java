package com.codeview.app.web;

import com.codeview.app.flow.FlowGraph;
import com.codeview.app.flow.FlowGraphService;
import com.codeview.app.model.TreeNode;
import com.codeview.app.okf.OkfReader;
import com.codeview.app.tree.TreeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * JSON data endpoints backing the developer UI pages (tree.html, flow.html).
 * Kept separate from McpController: these exist for this app's own UI to
 * fetch, not as part of the MCP-agent-facing surface documented in
 * docs/Architecture.md §9.
 *
 * <p><b>Project-scoped (this build):</b> {@code /ui/api/tree} and {@code
 * /ui/api/flow} take a {@code project} query param, same as their
 * McpController counterparts. {@link #projects} lists what's available so
 * the pages can populate a project picker.
 */
@RestController
@Tag(name = "UI data", description = "JSON endpoints consumed by the tree and flow visualization pages")
public class UiDataController {

    private final TreeService treeService;
    private final FlowGraphService flowGraphService;
    private final OkfReader okfReader;

    public UiDataController(TreeService treeService, FlowGraphService flowGraphService, OkfReader okfReader) {
        this.treeService = treeService;
        this.flowGraphService = flowGraphService;
        this.okfReader = okfReader;
    }

    @Operation(summary = "List projects (for the UI's project picker)")
    @GetMapping("/ui/api/projects")
    public List<String> projects() throws Exception {
        return okfReader.listProjects();
    }

    @Operation(summary = "Directory/file/symbol tree", description = "Same data as /mcp/code/tree, served for the UI's own fetch calls")
    @GetMapping("/ui/api/tree")
    public Map<String, TreeNode> tree(@RequestParam String project) throws Exception {
        return treeService.buildTree(project);
    }

    @Operation(summary = "Dependency flow graph", description = "Nodes = chunks, edges = resolved dependency links, within one project")
    @GetMapping("/ui/api/flow")
    public FlowGraph flow(@RequestParam String project) throws Exception {
        return flowGraphService.buildGraph(project);
    }
}

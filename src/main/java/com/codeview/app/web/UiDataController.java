package com.codeview.app.web;

import com.codeview.app.flow.FlowGraph;
import com.codeview.app.flow.FlowGraphService;
import com.codeview.app.model.TreeNode;
import com.codeview.app.tree.TreeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * JSON data endpoints backing the developer UI pages (tree.html, flow.html).
 * Kept separate from McpController: these exist for this app's own UI to
 * fetch, not as part of the MCP-agent-facing surface documented in
 * docs/Architecture.md §9.
 */
@RestController
@Tag(name = "UI data", description = "JSON endpoints consumed by the tree and flow visualization pages")
public class UiDataController {

    private final TreeService treeService;
    private final FlowGraphService flowGraphService;

    public UiDataController(TreeService treeService, FlowGraphService flowGraphService) {
        this.treeService = treeService;
        this.flowGraphService = flowGraphService;
    }

    @Operation(summary = "Directory/file/symbol tree", description = "Same data as /mcp/code/tree, served for the UI's own fetch calls")
    @GetMapping("/ui/api/tree")
    public Map<String, TreeNode> tree() throws Exception {
        return treeService.buildTree();
    }

    @Operation(summary = "Dependency flow graph", description = "Nodes = chunks (classes/methods), edges = resolved dependency links")
    @GetMapping("/ui/api/flow")
    public FlowGraph flow() throws Exception {
        return flowGraphService.buildGraph();
    }
}

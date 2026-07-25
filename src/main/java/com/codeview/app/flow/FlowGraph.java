package com.codeview.app.flow;

import java.util.List;

/**
 * Node/edge graph derived from OKF concept files' dependency fields, for the
 * developer-facing "flow of source code" visualization. Built the same way
 * the prompt-filter's LinkGraphWalker matches dependencies to chunk names —
 * this just does it for every chunk at once instead of a one-hop walk from
 * a single match, so the whole codebase's call/import structure renders as
 * one graph.
 */
public record FlowGraph(List<FlowNode> nodes, List<FlowEdge> edges) {

    public record FlowNode(String id, String label, String type, String filePath) {
    }

    public record FlowEdge(String from, String to) {
    }
}

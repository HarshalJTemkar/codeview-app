package com.codeview.app.flow;

import com.codeview.app.model.ChunkRecord;
import com.codeview.app.okf.OkfReader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds the developer-facing dependency flow graph: one node per chunk
 * (class or method), one edge per resolved dependency link. Matching logic
 * mirrors LinkGraphWalker (Architecture Plan §11) — deterministic string
 * matching against chunk names/paths, no model call.
 */
@Service
public class FlowGraphService {

    private final OkfReader okfReader;

    public FlowGraphService(OkfReader okfReader) {
        this.okfReader = okfReader;
    }

    public FlowGraph buildGraph() throws IOException {
        List<ChunkRecord> chunks = okfReader.readAll();

        List<FlowGraph.FlowNode> nodes = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        for (ChunkRecord chunk : chunks) {
            if (seenIds.add(chunk.chunkId())) {
                nodes.add(new FlowGraph.FlowNode(chunk.chunkId(), chunk.name(), chunk.astNode(), chunk.filePath()));
            }
        }

        List<FlowGraph.FlowEdge> edges = new ArrayList<>();
        for (ChunkRecord chunk : chunks) {
            for (String dep : chunk.dependencies()) {
                for (ChunkRecord candidate : chunks) {
                    if (candidate.chunkId().equals(chunk.chunkId())) {
                        continue; // no self-loops
                    }
                    boolean linked = dep.endsWith(candidate.name())
                            || candidate.filePath().contains(dep.replace('.', '/'));
                    if (linked) {
                        edges.add(new FlowGraph.FlowEdge(chunk.chunkId(), candidate.chunkId()));
                    }
                }
            }
        }

        return new FlowGraph(nodes, edges);
    }
}

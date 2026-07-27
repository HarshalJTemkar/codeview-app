package com.codeview.app.flow;

import com.codeview.app.model.ChunkRecord;
import com.codeview.app.okf.OkfReader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the developer-facing dependency flow graph: one node per chunk
 * (class or method), one edge per resolved dependency link. Matching logic
 * mirrors {@link com.codeview.app.promptfilter.LinkGraphWalker}
 * (Architecture Plan §11) — deterministic string matching against chunk
 * names/paths, no model call — run across every chunk instead of a one-hop
 * walk from a single prompt match.
 */
@Service
public class FlowGraphService {

    private final OkfReader okfReader;

    public FlowGraphService(OkfReader okfReader) {
        this.okfReader = okfReader;
    }

    /** Reads every indexed chunk and builds the full node/edge graph from their dependency fields. */
    public FlowGraph buildGraph(String project) throws IOException {
        List<ChunkRecord> chunks = okfReader.readAll(project);
        return new FlowGraph(buildNodes(chunks), buildEdges(chunks));
    }

    /** One node per distinct chunk ID — de-duplicated via a LinkedHashMap keyed by chunk_id, preserving first-seen order. */
    private List<FlowGraph.FlowNode> buildNodes(List<ChunkRecord> chunks) {
        Map<String, FlowGraph.FlowNode> nodesById = new LinkedHashMap<>();
        chunks.forEach(chunk -> nodesById.putIfAbsent(chunk.chunkId(),
                new FlowGraph.FlowNode(chunk.chunkId(), chunk.name(), chunk.astNode(), chunk.filePath())));
        return List.copyOf(nodesById.values());
    }

    /** One edge per (chunk, dependency, resolved target) triple — every chunk checked against every other chunk. */
    private List<FlowGraph.FlowEdge> buildEdges(List<ChunkRecord> chunks) {
        return chunks.stream()
                .flatMap(chunk -> edgesFrom(chunk, chunks))
                .toList();
    }

    /** Edges originating from one chunk: one per dependency string that resolves to another chunk in the set. */
    private java.util.stream.Stream<FlowGraph.FlowEdge> edgesFrom(ChunkRecord source, List<ChunkRecord> allChunks) {
        return source.dependencies().stream()
                .flatMap(dependency -> allChunks.stream()
                        .filter(candidate -> !candidate.chunkId().equals(source.chunkId()))
                        .filter(candidate -> resolves(dependency, candidate))
                        .map(candidate -> new FlowGraph.FlowEdge(source.chunkId(), candidate.chunkId())));
    }

    /** True if a dependency string plausibly refers to the candidate chunk, by name or by package-path containment. */
    private boolean resolves(String dependency, ChunkRecord candidate) {
        return dependency.endsWith(candidate.name())
                || candidate.filePath().contains(dependency.replace('.', '/'));
    }
}

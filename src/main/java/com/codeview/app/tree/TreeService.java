package com.codeview.app.tree;

import com.codeview.app.model.ChunkRecord;
import com.codeview.app.model.TreeNode;
import com.codeview.app.okf.OkfReader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the directory -> file -> symbol tree (Architecture Plan §8) from the
 * current set of OKF concept files. The UI is expected to lazy-load by
 * calling GET /mcp/code/tree with a path prefix rather than pulling the whole
 * tree at once for large repos — this service builds the requested subtree
 * on demand rather than caching a full tree in memory permanently.
 *
 * <p><b>Not written as a stream pipeline on purpose.</b> Each chunk's path
 * segments have to be folded into a shared, growing map of parent/child
 * links as they're processed — that's inherently stateful graph
 * construction, and forcing it into {@code .stream()} calls would trade
 * readability for the appearance of "modern style" without actually being
 * clearer. The loop below is kept, but split into small named methods so
 * each step (path-segment walk, node creation, symbol-leaf attachment)
 * reads on its own.
 */
@Service
public class TreeService {

    private static final String ROOT_ID = "root";

    private final OkfReader okfReader;

    public TreeService(OkfReader okfReader) {
        this.okfReader = okfReader;
    }

    /**
     * Reads every indexed chunk and folds them into one directory/file/symbol tree, keyed by node ID.
     * @param project which project's OKF store to build the tree from
     */
    public Map<String, TreeNode> buildTree(String project) throws IOException {
        List<ChunkRecord> chunks = okfReader.readAll(project);
        Map<String, TreeNode> nodes = new LinkedHashMap<>();
        nodes.computeIfAbsent(ROOT_ID, id -> new TreeNode(ROOT_ID, "/", "directory"));

        chunks.forEach(chunk -> foldChunkIntoTree(chunk, nodes));
        return nodes;
    }

    /**
     * Walks one chunk's file path segment-by-segment, creating a
     * directory/file node for each segment that doesn't already exist, then
     * attaches the chunk itself as a symbol leaf under its file node.
     */
    private void foldChunkIntoTree(ChunkRecord chunk, Map<String, TreeNode> nodes) {
        String[] pathSegments = chunk.filePath().split("/");
        String fileNodeId = walkPathSegments(pathSegments, nodes);
        attachSymbolLeaf(chunk, fileNodeId, nodes);
    }

    /**
     * Ensures a node exists for every path segment (directories, then the
     * file itself), linking each one under its parent. Returns the file
     * node's ID so the caller can attach the chunk's symbol leaf under it.
     */
    private String walkPathSegments(String[] pathSegments, Map<String, TreeNode> nodes) {
        String parentId = ROOT_ID;
        StringBuilder pathSoFar = new StringBuilder();

        for (int i = 0; i < pathSegments.length; i++) {
            boolean isFile = (i == pathSegments.length - 1);
            pathSoFar.append(i == 0 ? "" : "/").append(pathSegments[i]);
            String nodeId = "dir:" + pathSoFar;

            ensureNodeExists(nodes, nodeId, pathSegments[i], isFile ? "file" : "directory");
            linkChildToParent(nodes, parentId, nodeId);
            parentId = nodeId;
        }
        return parentId; // after the loop, parentId is the file node's own ID
    }

    /** Creates the chunk's symbol node (class/method) if it doesn't exist yet, and records this chunk under it. */
    private void attachSymbolLeaf(ChunkRecord chunk, String fileNodeId, Map<String, TreeNode> nodes) {
        String symbolNodeId = fileNodeId + "#" + chunk.name();
        TreeNode symbolNode = nodes.computeIfAbsent(symbolNodeId,
                id -> new TreeNode(id, chunk.name(), chunk.astNode()));
        symbolNode.getChunkIds().add(chunk.chunkId());
        linkChildToParent(nodes, fileNodeId, symbolNodeId);
    }

    /** Creates a node with the given ID/label/type if it isn't already in the map. */
    private void ensureNodeExists(Map<String, TreeNode> nodes, String nodeId, String label, String type) {
        nodes.computeIfAbsent(nodeId, id -> new TreeNode(id, label, type));
    }

    /** Registers childId under parentId's children list, without adding a duplicate if it's already there. */
    private void linkChildToParent(Map<String, TreeNode> nodes, String parentId, String childId) {
        TreeNode parent = nodes.get(parentId);
        if (!parent.getChildren().contains(childId)) {
            parent.getChildren().add(childId);
        }
    }
}

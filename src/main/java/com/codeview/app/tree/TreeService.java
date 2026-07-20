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
 */
@Service
public class TreeService {

    private final OkfReader okfReader;

    public TreeService(OkfReader okfReader) {
        this.okfReader = okfReader;
    }

    public Map<String, TreeNode> buildTree() throws IOException {
        List<ChunkRecord> chunks = okfReader.readAll();
        Map<String, TreeNode> nodes = new LinkedHashMap<>();

        TreeNode root = nodes.computeIfAbsent("root", id -> new TreeNode("root", "/", "directory"));

        for (ChunkRecord chunk : chunks) {
            String[] parts = chunk.filePath().split("/");
            String parentId = "root";
            StringBuilder pathSoFar = new StringBuilder();

            for (int i = 0; i < parts.length; i++) {
                pathSoFar.append(i == 0 ? "" : "/").append(parts[i]);
                String nodeId = "dir:" + pathSoFar;
                boolean isFile = (i == parts.length - 1);
                String type = isFile ? "file" : "directory";

                TreeNode node = nodes.computeIfAbsent(nodeId, id -> new TreeNode(id, parts[i], type));
                TreeNode parent = nodes.get(parentId);
                if (!parent.getChildren().contains(nodeId)) {
                    parent.getChildren().add(nodeId);
                }
                parentId = nodeId;

                if (isFile) {
                    String symbolNodeId = nodeId + "#" + chunk.name();
                    TreeNode symbolNode = nodes.computeIfAbsent(symbolNodeId,
                            id -> new TreeNode(id, chunk.name(), chunk.astNode()));
                    symbolNode.getChunkIds().add(chunk.chunkId());
                    if (!node.getChildren().contains(symbolNodeId)) {
                        node.getChildren().add(symbolNodeId);
                    }
                }
            }
        }

        return nodes;
    }
}

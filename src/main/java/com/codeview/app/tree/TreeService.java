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

        // Create Root
        TreeNode root = new TreeNode("root", "/", "directory");
        nodes.put("root", root);

        for (ChunkRecord chunk : chunks) {

            String[] parts = chunk.filePath().split("/");

            String parentId = "root";
            StringBuilder pathSoFar = new StringBuilder();

            for (int i = 0; i < parts.length; i++) {

                if (i > 0) {
                    pathSoFar.append("/");
                }
                pathSoFar.append(parts[i]);

                String nodeId = "dir:" + pathSoFar.toString();
                boolean isFile = (i == parts.length - 1);
                String type = isFile ? "file" : "directory";

                // Avoid computeIfAbsent()
                TreeNode node = nodes.get(nodeId);
                if (node == null) {
                    node = new TreeNode(nodeId, parts[i], type);
                    nodes.put(nodeId, node);
                }

                TreeNode parent = nodes.get(parentId);
                if (!parent.getChildren().contains(nodeId)) {
                    parent.getChildren().add(nodeId);
                }

                parentId = nodeId;

                if (isFile) {

                    String symbolNodeId = nodeId + "#" + chunk.name();

                    TreeNode symbolNode = nodes.get(symbolNodeId);
                    if (symbolNode == null) {
                        symbolNode = new TreeNode(
                                symbolNodeId,
                                chunk.name(),
                                chunk.astNode()
                        );
                        nodes.put(symbolNodeId, symbolNode);
                    }

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

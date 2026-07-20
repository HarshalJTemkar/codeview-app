package com.codeview.app.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Mirrors the tree node schema from the source design doc: id, label, type
 * (directory/file/class/function), children, chunk_ids, status (used for
 * changed-chunk highlighting in the UI).
 */
public class TreeNode {
    private String id;
    private String label;
    private String type;
    private final List<String> children = new ArrayList<>();
    private final List<String> chunkIds = new ArrayList<>();
    private String status = "unchanged";

    public TreeNode() {
    }

    public TreeNode(String id, String label, String type) {
        this.id = id;
        this.label = label;
        this.type = type;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public List<String> getChildren() {
        return children;
    }

    public List<String> getChunkIds() {
        return chunkIds;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}

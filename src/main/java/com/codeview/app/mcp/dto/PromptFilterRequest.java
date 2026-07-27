package com.codeview.app.mcp.dto;

/**
 * Body for POST /mcp/code/prompt_filter — the raw prompt text, plus which
 * project's OKF store to search (required — see GET /mcp/code/projects).
 */
public record PromptFilterRequest(String prompt, String project) {
}

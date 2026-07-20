package com.codeview.app.mcp.dto;

/** Body for POST /mcp/code/prompt_filter — the raw prompt text, nothing else. */
public record PromptFilterRequest(String prompt) {
}

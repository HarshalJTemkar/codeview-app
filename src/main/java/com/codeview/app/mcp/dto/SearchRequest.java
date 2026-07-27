package com.codeview.app.mcp.dto;

/**
 * Body for POST /mcp/code/search. Matches the source doc's shape:
 * { "query": "...", "top_k": 5 } — "top_k" is honored as a plain result-count
 * cap, not a ranking cutoff (there is no ranking function in this design).
 *
 * @param project which project's OKF store to search (required — see GET /mcp/code/projects to list available names)
 */
public record SearchRequest(String query, Integer topK, String project) {
}

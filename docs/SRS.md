# Software Requirements Specification — CodeView

## 1. Purpose

CodeView is a read-only code intelligence server that indexes a codebase into structured,
version-controlled concept files (Open Knowledge Format) and exposes them through an MCP-shaped
REST API — including a prompt-time filter that assembles relevant code context for an LLM call
without CodeView itself ever consuming an LLM token.

Source: adapted from *Designing a Lightweight, Low-Token CodeView Application (Executive
Summary)*, with the constraints and decisions recorded in `docs/Architecture.md`.

## 2. Scope

**In scope for this build:**
- Java source parsing (AST-based chunking via JavaParser)
- Merkle-style content hashing for change detection
- OKF concept-file generation (markdown + YAML frontmatter)
- Directory/file/symbol tree construction
- Parallel first-time indexing with per-worker fault handling
- File-watch-triggered incremental re-indexing
- MCP REST endpoints: search, get_chunk, tree, update_file, reindex_all, prompt_filter
- Prompt-time context filter (structural match → keyword fallback → link-graph walk)

**Explicitly out of scope for this build** (see README "What's deliberately not in this build"):
- Authentication/authorization of any kind
- Any language other than Java
- Postgres or any other secondary index
- Vector embeddings, vector database, or any LLM call inside CodeView itself
- Applying patches/diffs to source (CodeView only ever reads and re-reads files)
- A UI client (only the REST API this UI would call)

## 3. Functional Requirements

| ID | Requirement |
|---|---|
| FR-1 | The system SHALL parse `.java` files into an AST and emit one chunk per class/interface and one chunk per method. |
| FR-2 | The system SHALL compute a SHA-256 content hash for every chunk. |
| FR-3 | The system SHALL write each chunk as one OKF concept file (markdown + YAML frontmatter) containing: `chunk_id`, `file_path`, `language`, `name`, `start_line`, `end_line`, `tags`, `dependencies`, `hash`, `last_modified`, and the chunk's source text. |
| FR-4 | The system SHALL NOT write, modify, or delete any file inside the target repository under any circumstance. |
| FR-5 | The system SHALL detect changed files by comparing a newly computed content hash against the last known hash, and SHALL skip re-chunking a file whose hash is unchanged. |
| FR-6 | The system SHALL support a full first-time index that partitions files across a worker pool sized to available CPU cores (configurable override), with per-file retry on failure and no cross-worker coordination requirement. |
| FR-7 | The system SHALL report every file that failed indexing after exhausting retries; it SHALL NOT silently drop a failed file. |
| FR-8 | The system SHALL watch the target repository for file modifications and trigger incremental re-index of the changed file only. |
| FR-9 | The system SHALL expose a search endpoint that matches a query string against chunk name, file path, and tags — with no ranking function, embedding, or model call involved. |
| FR-10 | The system SHALL expose an endpoint to fetch a single chunk's full content by chunk ID. |
| FR-11 | The system SHALL expose an endpoint returning a directory → file → symbol tree derived from the current OKF concept files. |
| FR-12 | The system SHALL expose an endpoint that re-reads and re-chunks a single named file from disk; this endpoint SHALL NOT accept or apply a patch/diff. |
| FR-13 | The system SHALL expose a prompt-filter endpoint that: (a) attempts structural matching of explicit symbol names/paths in the prompt; (b) falls back to keyword matching against chunk name/path/tags if structural matching finds nothing; (c) performs a one-hop walk of dependency links from whatever matched; (d) returns matched and linked chunks, or an explicit `no_match: true` flag with empty results if nothing matched at any step. |
| FR-14 | The prompt-filter endpoint SHALL NOT call an LLM or any other model at any step; it returns context for the caller to use in its own subsequent LLM call. |

## 4. Non-Functional Requirements

| ID | Requirement |
|---|---|
| NFR-1 | No authentication, authorization, or RBAC mechanism SHALL be present on any endpoint. |
| NFR-2 | No embedding model, vector index, or vector database SHALL be present anywhere in the system. |
| NFR-3 | All chunk-write operations SHALL be idempotent (hash-keyed file naming), such that re-processing the same content after a crash or retry produces no duplicate data. |
| NFR-4 | The system SHALL be usable against any Java codebase supplied via configuration (`codeview.repo-root`) without code changes — i.e., it is not hard-coded to a specific project. |
| NFR-5 | Worker pool size SHALL default to the runtime's available processor count and SHALL be overridable via configuration. |

## 5. Traceability

Every requirement above traces to a specific section of `docs/Architecture.md`:
FR-1–FR-4 → §4; FR-5, FR-8 → §5; FR-6, FR-7 → §12; FR-9–FR-12 → §9; FR-13, FR-14 → §11;
NFR-1 → §9; NFR-2, NFR-3 → §7, §10; NFR-4 → §9 ("plugged to any application");
NFR-5 → §12 (confirmed).

## 6. Explicitly Deferred (Not a Requirement Yet)

- **Deployment target** (local-only vs. shared, vs. perimeter-restricted) — not decided.
  NFR-1 (no auth) is only as safe as this decision, whenever it's made.

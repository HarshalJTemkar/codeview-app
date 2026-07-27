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
- Accepting a directory, `.zip` archive, or single `.java` file as the source to index
- Merkle-style content hashing for change detection
- OKF concept-file generation (markdown + YAML frontmatter)
- Directory/file/symbol tree construction
- Parallel first-time indexing with per-worker fault handling
- File-watch-triggered incremental re-indexing
- MCP REST endpoints: search, get_chunk, tree, update_file, reindex_all, prompt_filter
- Prompt-time context filter (structural match → keyword fallback → link-graph walk)
- A Thymeleaf-based developer UI: tree visualization and dependency flow graph
- Browser upload of a `.zip` or a folder, indexed via the same pipeline as any other source
- Per-project isolation: multiple indexed sources kept in separate OKF subdirectories, never mixed
- OpenAPI/Swagger documentation, generated from the REST controller's own annotations

**Explicitly out of scope for this build** (see README "What's deliberately not in this build"):
- Authentication/authorization of any kind
- Any language other than Java
- Postgres or any other secondary index
- Vector embeddings, vector database, or any LLM call inside CodeView itself
- Applying patches/diffs to source (CodeView only ever reads and re-reads files)
- Editing capability in the UI — the tree/flow pages are read-only views; there is no
  in-browser code editor, no chunk-editing form, and no way to trigger a write from the UI

## 3. Functional Requirements

| ID | Requirement |
|---|---|
| FR-1 | The system SHALL parse `.java` files into an AST and emit one chunk per class/interface and one chunk per method. |
| FR-1a | The system SHALL accept a source location as a directory, a `.zip` archive, or a single `.java` file, and SHALL resolve each to the correct set of files to index without requiring different configuration or code paths from the caller. |
| FR-1b | When the source is a `.zip` archive, the system SHALL extract it to a temporary location for reading and SHALL reject any archive entry whose path would resolve outside that temporary location (zip-slip protection), skipping that entry rather than failing the whole extraction. |
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
| FR-15 | The system SHALL provide a browsable tree page rendering the directory → file → class → method structure, lazy-expandable per node, with the ability to view a symbol's full indexed chunk content. |
| FR-16 | The system SHALL provide a dependency flow visualization showing every resolved dependency link between chunks as a graph, using the same deterministic matching logic as the prompt-filter's link-graph walk (FR-13c), applied across the whole indexed set rather than one hop from a single match. |
| FR-17 | The system SHALL expose OpenAPI-compliant API documentation, generated from the REST controller's own annotations, browsable via a Swagger UI. |
| FR-18 | The system SHALL accept a `.zip` archive uploaded from the browser and index it via the same code path as any other `.zip` source. |
| FR-19 | The system SHALL accept a folder uploaded from the browser (as a set of individual files, each carrying its relative path), reconstruct it into an indexable directory preserving that structure, index only the `.java` files within it, and reject any relative path that would resolve outside the reconstruction directory. |
| FR-20 | The system SHALL clean up any temporary files or directories it creates to service an upload, whether the upload succeeds or fails. |
| FR-21 | The system SHALL keep multiple indexed sources fully isolated from one another under a per-project subdirectory of the OKF root, derived automatically from the source's name (zip filename, folder name, or path segment) — two projects with the same relative file path SHALL NOT collide or overwrite each other's data. |
| FR-22 | Every read endpoint (search, get_chunk, tree, flow, prompt_filter) SHALL require an explicit project name and SHALL only return data from that project's OKF subdirectory — never merged across projects. |
| FR-23 | The system SHALL expose an endpoint listing every currently-indexed project's name, for a caller to discover what's available before choosing one. |

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
- **Watching a `.zip`-configured source for changes** — not supported. The file watcher (FR-8)
  only applies to a directory-configured source; a `.zip` or single-file source must be
  re-indexed manually via `/mcp/code/reindex_all` after it changes.

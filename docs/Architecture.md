# CodeView Application — Adapted Architecture Plan

**Source document:** *Designing a Lightweight, Low-Token CodeView Application (Executive Summary)*
**This plan applies four constraints you gave, on top of the source design, with no other deviation:**

| # | Your constraint | How it's applied |
|---|---|---|
| 1 | No hallucination, no assumption | Every section below is traced to the source doc. Anything the source doc left unspecified is listed in §13 as **open**, not filled in. |
| 2 | No source code changes | CodeView is **read-only** against the target repo. It parses, hashes, indexes, and serves — it never writes back to source files. This is already implicit in the source doc (it only ever *reads* files for chunking); this plan makes it an explicit hard rule. |
| 3 | No auth / no OAuth | The source doc's MCP layer specifies OAuth2 + RBAC. That is **fully removed** — no bearer tokens, no OAuth, no RBAC, anywhere in the design. CodeView runs unauthenticated, assuming it is deployed in a trusted local/internal context (see §9 for the tradeoff this creates). |
| 4 | Zero token consumption (not "low token"), OKF instead of RAG | The source doc's "Token-Minimization Strategies" (summarization, RAG, delta updates) is replaced with **Open Knowledge Format (OKF)** — Google Cloud's June 2026 spec for representing knowledge as linked, version-controlled markdown+YAML concept files, read directly by agents instead of retrieved via embeddings at query time. No embedding model, no vector index, no LLM call anywhere in indexing or search. See §7. |

"Plugged to any application" is treated as: the MCP server and its APIs are language-agnostic and host-agnostic — any editor, agent, or app that can speak MCP over stdio/HTTP can attach, exactly as the source doc's MCP layer already intends, just without the auth gate.

---

## 1. What Stays Identical to the Source Document

These pieces are taken as-is, no changes:

- **Chunking**: AST/Tree-sitter based, greedy sibling-merge with recursive split on oversized nodes, ~500–1000 token budget per chunk (pluggable per language), non-whitespace character count as the size metric.
- **Hashing**: Per-chunk content hash (SHA-256/fingerprint), Merkle-style aggregation from chunk → file → directory → root, so a root-hash comparison identifies changed branches without re-hashing untouched code.
- **Metadata schema**: The chunk record (`chunk_id`, `file_path`, `language`, `start_line`/`end_line`, `ast_node`, `name`, `dependencies`, `tags`, `text`, `tokens`, `hash`, `last_modified`) and the tree node schema (`id`, `label`, `type`, `children`, `chunk_ids`, `icon`, `status`) are unchanged.
- **Tree UI model**: Lazy-loaded directory → file → symbol tree, on-demand child fetch, changed-chunk highlighting, in-tree search by name/tag.
- **Update triggers**: File watchers (inotify/FSNotify), VCS hooks on push/merge, and on-demand re-index requests — all trigger the same Merkle-diff → re-chunk-changed-only flow.
- **Fault tolerance**: Idempotent hash-keyed writes, retry/backoff on indexing jobs, eventual consistency with a "stale" UI flag during re-index, transactional writes with checksums, audit log of every update with actor ID.
- **Storage split**: OKF (markdown + YAML concept files, git-native) is the primary, always-on store. Postgres is an **optional** secondary index over it, not a requirement — added only if scale calls for faster structured queries than walking the OKF bundle directly. No SQLite, no vector DB.

## 2. What Changes

| Area | Source design | Adapted design | Why |
|---|---|---|---|
| Auth | OAuth2 bearer tokens, RBAC on every MCP endpoint | None — MCP endpoints are open on the local/internal network the process binds to | Your constraint #3 |
| Write path | "Ask CodeView to re-index a given file **or apply a patch**" (`update_file` could accept a diff to apply) | `update_file` only ever means "re-read this file from disk and re-chunk it." CodeView never applies a patch to source. | Your constraint #2 |
| Token strategy | Summarization via LLM, RAG top-k retrieval, delta-only prompts, compression | See §7 — no LLM call anywhere in the indexing or search path | Your constraint #4 |
| Tags | "Automatically derived tags... could come from comments or **LLM embedding clustering**" | Tags derived only from static signals: AST node type/name, import graph, comment text matched against a static keyword/regex tag list, and structural properties (e.g. "has-loop", "has-io-call" via AST pattern match) | Consistent with #4 — no model call to generate tags |

## 3. High-Level Architecture

```
Target Repo (read-only)
      │
      ▼
File Watcher / VCS Hook ──► Chunker (Tree-sitter/AST) ──► Hasher (Merkle)
                                                              │
                                                              ▼
                                          OKF Bundle (primary)   +  Postgres (optional
                                          (markdown+YAML concept    thin index, only if
                                           files, cross-linked,     scale requires it —
                                           git-native, source of    see §13)
                                           truth — see §7)
                                                              │
                                                              ▼
                                                        MCP Server (no auth)
                                                              │
                                          ┌───────────────────┼───────────────────┐
                                          ▼                   ▼                   ▼
                                     Tree/UI Client      Any MCP-speaking      CI job / script
                                     (lazy-load,          agent or editor      (re-index trigger,
                                      diff highlight)      (search/get_chunk)   report pull)
```

No box in this diagram calls out to an LLM. The only place an LLM can enter the picture is *outside* this system entirely — an agent on the right-hand side asking CodeView a question via MCP, then doing its own reasoning over the returned chunk text.

## 4. Chunking & Parsing

Unchanged from the source doc: Tree-sitter/AST-based, per language, with a plain line/brace-based fallback for unsupported or binary files. Overlap window (~100 chars) preserved at chunk boundaries so a reference like a variable declaration isn't orphaned.

**Read-only guarantee**: the chunker opens files with read access only; there is no code path anywhere in the pipeline that opens a target-repo file for write. This is worth stating as an explicit architectural invariant (not just an intention), so it can be enforced in code review / static checks.

## 5. Hashing & Incremental Indexing

Unchanged: content hash per chunk, Merkle aggregation up the tree, root-hash comparison to skip unchanged branches on re-index. Duplicate chunks (identical hash) across files are stored once.

## 6. Storage & Metadata

OKF concept files (§7) are the primary store — each chunk's structured fields (`file_path`, `language`, `ast_node`, `name`, `tags`, `hash`, `last_modified`, `dependencies`-as-links) live in that file's YAML frontmatter and body, version-controlled in git. The `embeddings` field from the source doc's schema is dropped entirely — no vector store in this design.

Postgres is **optional**, added only if querying the OKF bundle directly becomes too slow at scale (e.g. very large repos). When present, it's a thin index over the OKF files, not an independent source of truth — nothing lives in Postgres that isn't derivable by re-scanning the OKF bundle.

## 7. Knowledge Delivery — OKF Instead of RAG

This is the section that diverges most from the source doc, so it's laid out in full. **Note on sourcing**: OKF was published by Google Cloud in June 2026, after my training cutoff — the description below is drawn from web sources I checked just now, not prior knowledge, and is flagged as such rather than presented as something I already knew.

**What OKF is, in the terms relevant here**: a directory of markdown files, one per concept, each with a small YAML frontmatter block for structured fields and a body of human/agent-readable text, cross-linked to other concept files to form a navigable graph. It's version-controlled (git-native) and requires no special tooling or schema registry to read — an agent (or a person) opens a file and follows links, rather than a system embedding and retrieving chunks at query time.

**How it replaces RAG in this design**:
- **Concept generation, not embedding**: each chunk record from §6 (function, class, module) becomes one OKF concept file. The YAML frontmatter carries the same structured fields already in the chunk schema — `file_path`, `language`, `ast_node`, `name`, `tags`, `hash`, `last_modified` — with no new metadata invented.
- **Links instead of vector similarity**: the `dependencies` field (import graph, calls) becomes explicit cross-links between concept files, so "what does this function call" or "what calls this function" is a graph walk through committed files, not a similarity search.
- **Written once, read many times**: OKF's stated advantage over RAG is that curated knowledge is derived once and read directly, instead of being re-derived from raw chunks on every query. Since CodeView already re-chunks only changed files on each Merkle diff (§5), regenerating only the changed concept files on re-index is a natural fit, not an added cost.
- **No query-time computation**: `/mcp/code/search` becomes a lookup over the OKF bundle's frontmatter/links (by name, tag, path, or link-adjacency) rather than a ranked retrieval step. There's no ranking function, embedding call, or LLM call involved at all — the agent reading the result does whatever reasoning it needs on its own token budget, outside CodeView.

**What's explicitly removed from the source doc's plan**: LLM-generated chunk summaries, LLM-based tag clustering, vector embeddings, vector database, and RAG-style "send top-k chunks to a model" as something CodeView itself does.

**Open item flowing from this** (see §13): whether the OKF bundle is generated as a byproduct of indexing and stored alongside the Postgres metadata, or treated as the primary store with Postgres reduced to an index over it — this has since been confirmed as the latter (OKF primary), see §13.

## 8. Tree / UI Model

Unchanged — see §1. Lazy load per node expansion, changed-chunk highlighting, in-tree filter by name/tag, breadcrumbs and syntax highlighting within a chunk view.

## 9. MCP Layer — No Auth, Any Application

Endpoints, unchanged in shape from the source doc, minus the auth wrapper:

- `POST /mcp/code/search` — lookup over OKF concept files (by name, tag, path, or link-adjacency), returns chunk IDs + excerpts, no ranking/embedding step
- `POST /mcp/code/get_chunk` — fetch full chunk text (or its OKF concept file) by ID
- `POST /mcp/code/update_file` — re-read and re-chunk a file from disk (never applies a patch — see §2)
- `GET /mcp/code/tree` — serialized tree/subtree
- `POST /mcp/code/prompt_filter` — the prompt-time context filter (§11): takes raw prompt text, returns matched chunks/OKF context for the caller to attach before its own LLM call

**Removed**: the bearer-token check and RBAC gate on each of these.

**Tradeoff this creates, stated plainly rather than glossed over**: without auth, any process that can reach the MCP server's bind address can read the entire indexed codebase and trigger re-indexing. The source doc's own "Security" section calls for TLS, encryption at rest, and RBAC specifically because of this exposure. Whether this is acceptable depends entirely on the deployment target — and that's currently **undecided** (§13). If it ends up local-only (`localhost` bind), the OS boundary covers this. If it ends up shared on any network, this exposure is real and unmitigated until a deployment decision plus network-level controls are in place. This isn't a recommendation to add auth back — it's flagging that the risk here is currently open, not closed.

"Plugged to any application" is satisfied by these endpoints being transport-agnostic MCP (stdio or HTTP, per the source doc) — any host that speaks MCP can attach without CodeView needing to know anything about that host.

## 10. Fault Tolerance

Unchanged from source doc — idempotent hash-keyed operations, retry/backoff, eventual consistency with a stale-flag in the UI, transactional writes, audit logging, and the same crash-recovery story (Merkle comparison on restart avoids reprocessing unchanged branches). §12 extends this specifically to the first-time indexing run.

## 11. Prompt-Time Context Filter — the Core Feature

This is the feature you called out as most prominent, so it's specified in full. It sits in front of any LLM call a user or agent makes and works as a filter, not a generator:

**Pipeline, in order:**
1. **Intercept the prompt.** Before the prompt reaches the LLM, CodeView reads its text.
2. **Structural match (first pass).** Parse the prompt for explicit symbol references — file paths, function names, class names actually mentioned in the text (e.g. "fix the bug in `computeStats`"). If found, this is a direct, high-confidence hit straight to the matching OKF concept file(s)/chunk(s).
3. **Keyword/tag fallback (second pass).** If no explicit symbol is named (e.g. "why is the auth flow slow"), fall back to matching prompt terms against OKF frontmatter — `name`, `tags`, `file_path`. Lower precision than step 2, but covers the more common case of a prompt that doesn't name exact code.
4. **Link-graph walk (third pass).** From whatever matched in step 2 or 3, follow that concept file's OKF cross-links (calls, called-by, imports) to pull in directly related concepts — not just the one isolated chunk, but its immediate neighborhood.
5. **Assemble and forward.** The matched chunk/OKF content is attached to the original prompt, and *that combined package* is what goes to the LLM.

**Why this stays inside the zero-token constraint**: every step above — parsing prompt text, matching names/tags, walking links — is deterministic string/graph matching against already-generated OKF data. No model is called to decide what's relevant. The only token cost in the whole pipeline is the LLM call at step 5, which is the user's own request going out anyway — CodeView doesn't add a hidden LLM call to figure out what to send.

**MCP surface for this**: a new endpoint, `POST /mcp/code/prompt_filter`, taking the raw prompt text and returning the assembled context (matched chunks + OKF concepts) for the caller to attach before sending to an LLM — rather than CodeView calling the LLM itself. This keeps CodeView as a pure filter/context-assembly service, consistent with §9's "any application can plug in" design: the calling application or agent still owns the actual LLM call.

**No-match behavior (resolved)**: if steps 2–4 find nothing, the endpoint returns **empty context plus an explicit `no_match: true` flag** — not a fallback like the project overview. A filter's job is to pass through only genuine matches; guessing at relevance when there is none would be injecting an assumption, not filtering. The calling application decides what to do next (send the bare prompt, ask the user to be more specific, etc.) — that decision stays outside CodeView.

## 12. Parallel First-Time Indexing

For the initial full index of a codebase (not incremental re-index, which is already covered by Merkle diffing in §5), chunking is split across multiple parallel workers rather than run single-threaded:

- **Work partitioning**: split by directory or file, so each worker owns a disjoint set of files — no coordination needed between workers during chunking itself, since chunks are independent until they're written to the store.
- **Worker count (resolved)**: scale to CPU core count at runtime, with a configurable cap/override and a floor of 1 worker on single-core hardware — rather than a fixed pool size. This is what the source doc's own performance targets already assume (multi-core throughput to hit ~5–20s for 1M LOC); a fixed count would either underuse available cores or need re-tuning per machine.
- **Fault handling per worker**: if a worker fails on a given file (parse error, unreadable file, oversized file), that failure is isolated and retried per §10's existing retry/backoff rule — it doesn't stop or restart the other workers' progress.
- **Idempotent writes**: since every chunk is hash-keyed (§5), a retried or re-run worker can safely re-write a chunk/OKF file without creating duplicates — this is the same idempotency guarantee §10 already establishes, just relied on here under concurrent writers instead of sequential ones.
- **Completion**: the first-time index is done when every partitioned file has either succeeded or exhausted its retries; failed files are reported, not silently dropped.

This directly reuses the source doc's own performance targets (§ in source doc: ~5–20s initial index for 1M LOC on multi-core hardware) — parallel workers are how that target is actually met, rather than a new claim being introduced here.

## 13. Open Decisions — Not Specified in the Source Doc, Not Assumed Here

Per constraint #1, these are named rather than silently decided. Status as of your last round of answers — everything here is now either confirmed or explicitly deferred, not left ambiguous:

- **Language coverage** — *Confirmed*: Java first, per the earlier recommendation.
- **Deployment target** — *Explicitly deferred, not a current requirement*. Not a decision made — a decision postponed. §9's auth tradeoff remains an open risk until this is addressed, whenever that happens.
- **Storage backend** — *Confirmed*: no SQLite. Filesystem/OKF is the default, always-on store; Postgres is an **optional** secondary index, not a requirement.
- **OKF vs. Postgres primacy** — *Confirmed*: OKF is the primary source of truth. Postgres, when used at all, is a thin index over it.
- **Prompt-filter no-match behavior** (§11) — *Confirmed*: empty context + `no_match: true` flag, no fallback guess.
- **Worker count/partitioning strategy** (§12) — *Confirmed*: scales to CPU core count at runtime, configurable cap, floor of 1.

## 14. Suggested Build Phases

1. Chunker + hasher (read-only, single language first) → local metadata store
2. Merkle-based incremental re-index on file watch
3. OKF concept-file generation from chunk records (frontmatter + cross-links), regenerated only for changed chunks
4. Parallel-worker first-time indexing (§12), with per-worker fault handling
5. Tree/UI schema + lazy-load API (no client yet, just the endpoints)
6. MCP server: `search` (OKF lookup), `get_chunk`, `tree`, `update_file` — no auth
7. Prompt-time context filter (§11): structural match → keyword fallback → link-graph walk → `prompt_filter` endpoint
8. File watcher + VCS hook wiring for automatic re-index
9. UI client (tree view, diff highlighting, in-tree search)

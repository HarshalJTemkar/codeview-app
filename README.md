# CodeView

A read-only, zero-token, no-auth code intelligence server: AST-based chunking, Merkle-hashed
incremental indexing, an Open Knowledge Format (OKF) knowledge store instead of RAG/embeddings,
and an MCP-shaped REST API — including a prompt-time context filter that assembles relevant
code context before a prompt goes to an LLM.

Full design rationale, every confirmed decision, and every open item: see `docs/Architecture.md`.

## What this actually is — read before assuming more than what's here

This is a **single deployable Spring Boot application**, structured internally by component
(chunker, hasher, OKF store, indexer, prompt-filter, MCP controller) — not physically separate
microservices with their own ports/JVMs. The design plan never specified inter-service transport
or discovery, and inventing that now would be an unstated assumption, not a decision you made.

**Build/test verification status, stated plainly:** this code was written and reviewed carefully,
and the small set of dependency-free model classes were compiled directly with `javac` and pass —
that check is unaffected by the Spring Boot version, since those classes have no Spring/JavaParser
imports. The full application (Spring Boot 4.1.0, JavaParser, SnakeYAML) could **not** be compiled
or run in the environment this was written in, because Maven Central wasn't reachable on that
network's allowlist. Run `mvn clean test` yourself before relying on this — see below.

**Two specific risks from the Spring Boot 3.5 → 4.1 jump that I could not verify without
compiling, flagged rather than assumed away:**
- **Jackson 3.x** (bundled with Boot 4.x) is a breaking upgrade from Jackson 2.x — different
  module structure, stricter type handling. The controller returns plain records and `Map`
  objects with no custom Jackson annotations, which is the safest shape for this kind of jump,
  but I have not confirmed the JSON actually serializes the way the code implies.
- **JUnit 6** ships with `spring-boot-starter-test` in Boot 4.x. `ChunkerOkfPipelineTest` uses
  standard JUnit 5 Jupiter annotations (`@Test`, `@BeforeEach`, `@TempDir`) — these are expected
  to keep working, but again, unverified by an actual build.

Two changes that *don't* affect this app, checked against the Boot 4 breaking-changes list: no
Undertow (default is Tomcat), and no Spring Security starter (so the CSRF-default change some
teams hit doesn't apply — this app already had no security filter chain by design, per NFR-1).

## Developer UI

| Page | URL | Purpose |
|---|---|---|
| Landing | `/ui` | Links to everything below |
| Upload | `/ui/upload` | **Upload a .zip or a folder from the browser** and index it — see below |
| Tree | `/ui/tree` | Directory → file → class → method tree, expandable; click a symbol to read its indexed chunk |
| Flow | `/ui/flow` | Dependency graph — one node per chunk, one edge per resolved dependency link (via [vis-network](https://visjs.github.io/vis-network/), loaded from a CDN) |
| API docs | `/swagger-ui.html` | Interactive OpenAPI docs, generated from the `@RestController` annotations — not a hand-maintained spec |

The UI's visual theme (colors, type scale, radius scale, spacing rhythm) is adapted from a design
system reference you provided — the numeric token *values* only, not any brand name, logo, or
proprietary font from that source.

Both `/ui/tree` and `/ui/flow` fetch their data client-side from `/ui/api/tree` and `/ui/api/flow`
(`UiDataController`) — separate from the MCP-agent-facing endpoints in `McpController`, since these
exist only to back this app's own pages.

### Uploading from the browser

`/ui/upload` has two forms:
- **Zip upload** — a plain `<input type="file" accept=".zip">`, posted to `POST /mcp/code/upload/zip`.
- **Folder upload** — uses the browser's `webkitdirectory` folder picker. Each selected file is sent
  as a separate multipart part under the field name `files`, with its relative path set as the
  filename (`formData.append('files', file, file.webkitRelativePath)`) — that's what lets the server
  reconstruct the folder structure. Only `.java` files are kept; everything else is ignored, and any
  relative path that would escape the reconstruction directory is rejected (same zip-slip-style
  guard `SourceResolver` applies to `.zip` entries).

**Known browser limitation, stated plainly:** `webkitdirectory` folder selection is well supported in
Chromium- and Firefox-based browsers. Safari's support for it has historically been inconsistent —
if the folder picker doesn't behave as expected there, use the .zip upload instead. This isn't
something I could test directly in the environment this was written in; flagging it rather than
asserting cross-browser behavior I haven't verified.

Both upload paths land in the exact same `ParallelIndexer.indexSource()` call that
`POST /mcp/code/reindex_all` uses — uploading is just another way of pointing at a source, not a
separate indexing implementation.

## Multi-project isolation

Every indexed source now lands in its own subdirectory under `codeview.okf-root`, named after the
source itself:

- `.zip` upload `my-service.zip` → `okf-store/my-service/`
- Folder upload (browser picker selects `my-app/`) → `okf-store/my-app/`
- `codeview.repo-root` or a `reindex_all` `sourcePath` pointing at `/path/to/some-service` →
  `okf-store/some-service/`

**Why this exists:** before this, every indexed source wrote into one flat `okf-store/` directory.
Indexing a second project mixed its chunks in with the first — `OkfReader.readAll()` had no way to
tell them apart, so the tree, flow graph, search, and prompt-filter were all silently working off a
blended, wrong dataset the moment more than one project was ever indexed.

**Reads now require a `project` parameter, explicitly, on every call** — `search`, `get_chunk`,
`tree`, the UI's `/ui/api/tree` and `/ui/api/flow`, and `prompt_filter` all take one. This was a
deliberate choice over two alternatives:
- *Server remembers a "current" project* — rejected: global mutable state that races the moment two
  callers (two browser tabs, two agents) are indexing or reading different projects at once.
- *Merge everything into one aggregate view* — rejected: defeats the entire point of separating
  projects in the first place.

`GET /mcp/code/projects` (and `/ui/api/projects` for the UI's own use) lists what's actually been
indexed, so a caller — or the tree/flow pages' project picker dropdown — can discover valid names
rather than guessing. Every indexing response (`reindex_all`, both upload endpoints) includes a
`project` field telling you exactly what name to use for the reads that follow.

**Project names are sanitized**, not used raw: non-alphanumeric characters become dashes, leading
dots are stripped, and — independently, as defense-in-depth rather than trusting the sanitizer
alone — `ProjectStore` refuses to resolve any name that would still escape the OKF root once
resolved. A deliberately malicious path like `../../etc/passwd` degrades safely to just its last
path segment (`passwd`) before sanitization ever runs, since `Path.getFileName()` only ever looks
at the final segment regardless of what precedes it.

## Fixed since the last handoff

Two real bugs, found by actually running this against its own source and a folder upload:

- **Chunker failed on records, text blocks, and pattern-matching `instanceof`.** `JavaAstChunker`
  used `StaticJavaParser.parse()`, whose default language level is Java 8. Fixed by giving it its
  own `JavaParser` instance configured with `LanguageLevel.BLEEDING_EDGE` — also removes a latent
  thread-safety concern, since `StaticJavaParser`'s shared static configuration was being read
  concurrently by the parallel worker pool (§12). Covered by `JavaAstChunkerModernSyntaxTest`.
- **Folder upload could exceed the multipart size limit before any filtering happened.** Non-`.java`
  files (build output, `.git/`) were being uploaded in full and only filtered out server-side,
  after the whole request already had to fit under the size cap. Fixed by filtering client-side in
  `upload.js`, before anything is appended to the request. The `.zip` path can't be pre-filtered
  the same way (it's one opaque file) — `UploadController` now returns a clear, actionable error
  instead of a raw 500 if a zip is still too large, telling you to exclude `target/`/`.git/` before
  zipping.

## Design patterns used, and where

Applied where they genuinely fit the problem, not sprinkled in for their own sake — this build
went through a deep refactor pass on the business-logic core (`chunker`, `promptfilter`, `flow`,
`tree`); DTOs and controllers were left as they were, since they're already thin and single-purpose:

- **Strategy + Chain of Responsibility** — `PromptMatchStrategy` is implemented by `StructuralMatcher`
  and `KeywordMatcher`; `PromptFilterService` runs them as an explicit ordered chain, stopping at the
  first one that matches (§11's confirmed "structural first, keyword fallback"). The chain order is
  fixed in code, not left to Spring's ambient bean-injection order.
- **Builder** — `ChunkRecord.Builder`. The record has 12 fields; the old call sites were positional
  12-argument constructor calls, easy to get silently out of order. The builder makes each call site
  named and order-independent.
- **Factory-style dispatch** — `SourceResolver.resolve()` inspects the input and routes to
  `resolveDirectory` / `resolveZip` / `resolveSingleFile`, each returning the same `ResolvedSource`
  shape so nothing downstream needs to know which one ran.
- **Repository-like access** — `OkfReader`/`OkfWriter` are the only components that touch the OKF
  store directly; every other service (tree, flow, prompt-filter) goes through them rather than
  reading files itself.

**Method size / reuse:** the deep-refactor-pass files were split so each private method does one
named thing — e.g. `JavaAstChunker.chunk()` is now `parse` → `extractImportNames` →
`chunkClassAndItsMethods` → `toChunkRecord`, each with its own Javadoc, instead of one long method.
`TreeService` is a deliberate exception: it's kept as a documented loop rather than forced into a
stream pipeline, because folding chunks into a shared, growing parent/child map is inherently
stateful — the class-level Javadoc explains why.

**Streams/records/lambdas:** used throughout the refactored files where they read more clearly than
a loop (see `FlowGraphService`, `LinkGraphWalker`, both matcher classes) — not applied
indiscriminately where a loop is genuinely clearer (`TreeService`, as above).

## Diagrams

`docs/DIAGRAMS.md` has two Mermaid diagrams, both generated from the actual code (real `import`
statements and real controller/service wiring), not drawn from memory:
1. **Package dependency graph** — which package depends on which, and the regeneration command if
   the code changes.
2. **Whole-application architecture / request flow** — every caller, controller, service, and data
   store, including where the "read-only against source" guarantee is structurally visible (nothing
   points into the target-repo node).

## Requirements

- Java 21
- Maven 3.8+
- Network access to Maven Central (for the first build, to download dependencies)

## Build and run

```bash
mvn clean test        # runs ChunkerOkfPipelineTest — verify this passes first
mvn spring-boot:run
```

By default it indexes the small sample project at `sample-repo/` and writes OKF concept files to
`okf-store/`. Point `codeview.repo-root` in `src/main/resources/application.yml` at your own
codebase instead — it accepts three shapes, auto-detected: a **directory**, a **.zip archive**, or
a **single .java file**.

## Trigger a full index

```bash
# Uses codeview.repo-root from application.yml
curl -X POST http://localhost:8080/mcp/code/reindex_all

# Or override the source for this one call — any of the three shapes:
curl -X POST http://localhost:8080/mcp/code/reindex_all \
  -H "Content-Type: application/json" \
  -d '{"sourcePath": "/path/to/some/project"}'

curl -X POST http://localhost:8080/mcp/code/reindex_all \
  -H "Content-Type: application/json" \
  -d '{"sourcePath": "/path/to/uploaded/project.zip"}'

curl -X POST http://localhost:8080/mcp/code/reindex_all \
  -H "Content-Type: application/json" \
  -d '{"sourcePath": "/path/to/SingleFile.java"}'
```

Runs the parallel worker pool (Architecture Plan §12) over every `.java` file resolved from that
source, writing one OKF concept file per class/method chunk. A `.zip` is extracted to a temp
directory first (with zip-slip protection — malicious entry paths are skipped, not followed) and
cleaned up after indexing; the original zip file itself is never modified.

**Note on the file watcher and non-directory sources:** the file watcher (auto re-index on save)
only applies when `codeview.repo-root` is a directory. If it's configured as a `.zip` or single
file, the watcher logs that it isn't starting and you re-run `/mcp/code/reindex_all` manually
after the source changes — there's no way to "watch" a zip's contents changing without re-extracting it.

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/mcp/code/projects` | List every currently-indexed project's name |
| POST | `/mcp/code/search` | Keyword/structural lookup — requires `project` in the body |
| GET | `/mcp/code/get_chunk/{chunkId}` | Fetch one chunk by ID — requires `?project=` |
| GET | `/mcp/code/tree` | Directory → file → symbol tree — requires `?project=` |
| POST | `/mcp/code/update_file` | Re-read + re-chunk one file (never applies a patch); `project` optional, defaults to the one derived from `codeview.repo-root` |
| POST | `/mcp/code/reindex_all` | Full parallel index — of `codeview.repo-root`, or an override `sourcePath` (directory, zip, or single file); project name is derived automatically, returned in the response |
| POST | `/mcp/code/prompt_filter` | **The core feature** — requires `project` in the body — see below |

### Example: full index and the project it lands in

```bash
curl -X POST http://localhost:8080/mcp/code/reindex_all
# { "project": "sample-repo", "succeededFiles": [...], "chunksWritten": 3, ... }

curl "http://localhost:8080/mcp/code/tree?project=sample-repo"
```

### Example: prompt-time context filter

```bash
curl -X POST http://localhost:8080/mcp/code/prompt_filter \
  -H "Content-Type: application/json" \
  -d '{"prompt": "why is the sum method in MathUtils slow", "project": "sample-repo"}'
```

Returns matched chunks (structural match on `sum`/`MathUtils`, then a one-hop link-graph walk),
or `{"noMatch": true, ...}` with empty arrays if nothing matched — never a guessed fallback.
The caller (your own agent/app) attaches the returned context to the prompt before calling an LLM;
CodeView itself never makes that call.

## What's deliberately not in this build

- **No auth, no OAuth, no RBAC** — every endpoint above is open. Safe for local/localhost use;
  a real risk if exposed on a shared network without your own perimeter controls. Deployment
  target was explicitly left open in the plan — see `docs/Architecture.md`.
- **No Postgres integration** — the plan allows an optional Postgres index over the OKF bundle
  for scale, but it isn't wired up in this build. OKF (plain files on disk) is the only store.
- **No vector DB, no embeddings, no LLM call anywhere in indexing or search** — by design.
- **Java only** — confirmed scope for this build; other languages would need their own chunker.
- **Fixed-line/brace fallback chunker** (for unsupported languages) — not implemented; only
  the AST chunker for `.java` files exists.

## Project layout

```
src/main/java/com/codeview/app/
  chunker/       AST-based chunking (JavaParser)
  hash/          SHA-256 content hashing, Merkle aggregation
  source/        Resolves a directory, .zip, or single file into a walkable file list
  project/       Derives and validates the per-project OKF subdirectory name (multi-project isolation)
  okf/           OKF concept-file read/write (the "no RAG" knowledge store), project-scoped
  tree/          Directory/file/symbol tree builder
  index/         Parallel first-time indexer, incremental re-index, file watcher
  promptfilter/  Structural match → keyword fallback → link-graph walk
  mcp/           REST controller exposing the endpoints above (also the OpenAPI/Swagger source)
  flow/          Dependency flow graph builder (nodes = chunks, edges = resolved links)
  upload/        Browser upload handling (.zip and folder), reconstructs into an indexable source
  web/           Thymeleaf page controller + JSON endpoints backing the UI pages
  config/        Executor sizing, bound configuration properties, OpenAPI metadata
docs/
  Architecture.md   Full design plan, every decision and open item
  SRS.md            Software requirements specification
  Workflow.md        Indexing and prompt-filter sequence flows
```

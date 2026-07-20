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
codebase instead.

## Trigger a full first-time index

```bash
curl -X POST http://localhost:8080/mcp/code/reindex_all
```

Runs the parallel worker pool (Architecture Plan §12) over every `.java` file under
`codeview.repo-root`, writing one OKF concept file per class/method chunk.

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| POST | `/mcp/code/search` | Keyword/structural lookup over OKF concept files |
| GET | `/mcp/code/get_chunk/{chunkId}` | Fetch one chunk by ID |
| GET | `/mcp/code/tree` | Directory → file → symbol tree |
| POST | `/mcp/code/update_file` | Re-read + re-chunk one file (never applies a patch) |
| POST | `/mcp/code/reindex_all` | Full parallel first-time index |
| POST | `/mcp/code/prompt_filter` | **The core feature** — see below |

### Example: prompt-time context filter

```bash
curl -X POST http://localhost:8080/mcp/code/prompt_filter \
  -H "Content-Type: application/json" \
  -d '{"prompt": "why is the sum method in MathUtils slow"}'
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
  okf/           OKF concept-file read/write (the "no RAG" knowledge store)
  tree/          Directory/file/symbol tree builder
  index/         Parallel first-time indexer, incremental re-index, file watcher
  promptfilter/  Structural match → keyword fallback → link-graph walk
  mcp/           REST controller exposing the endpoints above
  config/        Executor sizing, bound configuration properties
docs/
  Architecture.md   Full design plan, every decision and open item
  SRS.md            Software requirements specification
  Workflow.md        Indexing and prompt-filter sequence flows
```

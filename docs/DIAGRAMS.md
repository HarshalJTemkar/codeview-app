# Diagrams

Both diagrams below are derived directly from the actual source (`grep`-ed import statements and
controller/service wiring), not hand-drawn from memory — see the note at the end of each section
for how to regenerate them if the code changes.

## 1. Package dependency graph

Every arrow below is a real `import com.codeview.app.*` found in the source at the time this was
generated. No package here imports back "up" the arrow direction — there are no cycles.

```mermaid
graph TD
    model["model<br/><i>ChunkRecord, TreeNode,<br/>IndexRunResult, PromptFilterResult</i>"]
    hash["hash<br/><i>MerkleHasher</i>"]
    config["config<br/><i>CodeViewProperties, ExecutorConfig,<br/>OpenApiConfig</i>"]
    source["source<br/><i>SourceResolver, ResolvedSource,<br/>SourceType</i>"]
    project["project<br/><i>ProjectNameResolver, ProjectStore</i>"]
    okf["okf<br/><i>OkfWriter, OkfReader</i>"]
    chunker["chunker<br/><i>JavaAstChunker</i>"]
    tree["tree<br/><i>TreeService</i>"]
    flow["flow<br/><i>FlowGraphService, FlowGraph</i>"]
    promptfilter["promptfilter<br/><i>PromptFilterService,<br/>StructuralMatcher, KeywordMatcher,<br/>LinkGraphWalker</i>"]
    index["index<br/><i>ParallelIndexer,<br/>IncrementalIndexService,<br/>FileWatcherService, FileHashStore</i>"]
    upload["upload<br/><i>UploadService, UploadController</i>"]
    mcp["mcp<br/><i>McpController</i>"]
    web["web<br/><i>UiPageController, UiDataController</i>"]

    okf --> model
    okf --> project
    chunker --> hash
    chunker --> model
    tree --> model
    tree --> okf
    flow --> model
    flow --> okf
    promptfilter --> model
    promptfilter --> okf
    index --> chunker
    index --> config
    index --> hash
    index --> model
    index --> okf
    index --> project
    index --> source
    upload --> index
    upload --> model
    upload --> project
    upload --> source
    mcp --> config
    mcp --> index
    mcp --> model
    mcp --> okf
    mcp --> project
    mcp --> promptfilter
    mcp --> tree
    web --> flow
    web --> model
    web --> okf
    web --> tree
```

**Reading this:** `model` and `okf` are still the two packages everything else eventually depends
on. The new addition is `project` — every package that writes or reads OKF data now depends on it
too (`okf`, `index`, `upload`, `mcp`), since that's what turns a project name into (and validates)
an actual filesystem path. `project` itself has zero CodeView dependencies, same as `source` and
`hash` — it's a leaf utility package, deliberately kept that way so it can be depended on from
everywhere without creating a cycle.

**To regenerate this if the code changes:**
```bash
cd src/main/java/com/codeview/app
for pkg in $(find . -mindepth 1 -maxdepth 1 -type d | sed 's|./||'); do
  echo "=== $pkg ==="
  grep -rhoE "import com\.codeview\.app\.[a-z]+" "$pkg" | sed 's/import com.codeview.app.//' | sort -u | grep -v "^$pkg$"
done
```

## 2. Whole-application architecture / request flow

```mermaid
flowchart TB
    subgraph Callers["External callers"]
        Browser["Browser<br/>(/ui/* pages)"]
        Agent["MCP agent / editor / CI script<br/>(/mcp/code/* endpoints)"]
    end

    subgraph WebLayer["Web layer"]
        UiPageController["UiPageController<br/>(Thymeleaf pages)"]
        UiDataController["UiDataController<br/>(JSON for tree/flow pages)"]
        McpController["McpController<br/>(search, get_chunk, tree,<br/>update_file, reindex_all,<br/>prompt_filter)"]
        UploadController["UploadController<br/>(zip, folder upload)"]
        SwaggerUI["/swagger-ui.html<br/>(generated from annotations)"]
    end

    subgraph ServiceLayer["Service layer"]
        SourceResolver["SourceResolver<br/>(directory / zip / file →<br/>walkable file list)"]
        ParallelIndexer["ParallelIndexer<br/>(worker pool, §12)"]
        IncrementalIndexService["IncrementalIndexService<br/>(Merkle-diff re-index, §5)"]
        FileWatcherService["FileWatcherService<br/>(WatchService)"]
        JavaAstChunker["JavaAstChunker<br/>(JavaParser AST → chunks)"]
        MerkleHasher["MerkleHasher<br/>(SHA-256 content hash)"]
        TreeService["TreeService"]
        FlowGraphService["FlowGraphService"]
        PromptFilterService["PromptFilterService<br/>(structural → keyword →<br/>link-graph walk, §11)"]
        UploadService["UploadService"]
    end

    subgraph DataLayer["Data layer"]
        OkfWriter["OkfWriter"]
        OkfReader["OkfReader"]
        TargetRepo[("Target source<br/>(read-only: directory,<br/>zip, or file)")]
        OkfStore[("OKF concept files<br/>(markdown + YAML,<br/>primary store, §7/§13)")]
        FileHashStore[("FileHashStore<br/>(.file-hashes.properties)")]
    end

    Browser --> UiPageController
    Browser --> UiDataController
    Browser -->|upload zip/folder| UploadController
    Agent --> McpController
    Agent -.->|reads docs| SwaggerUI

    UiDataController --> TreeService
    UiDataController --> FlowGraphService
    McpController --> TreeService
    McpController --> PromptFilterService
    McpController --> ParallelIndexer
    McpController --> IncrementalIndexService
    McpController --> OkfReader

    UploadController --> UploadService
    UploadService --> ParallelIndexer
    UploadService --> SourceResolver

    ParallelIndexer --> SourceResolver
    ParallelIndexer --> JavaAstChunker
    ParallelIndexer --> OkfWriter
    IncrementalIndexService --> MerkleHasher
    IncrementalIndexService --> JavaAstChunker
    IncrementalIndexService --> OkfWriter
    IncrementalIndexService --> FileHashStore
    FileWatcherService --> IncrementalIndexService
    FileWatcherService --> SourceResolver
    JavaAstChunker --> MerkleHasher

    TreeService --> OkfReader
    FlowGraphService --> OkfReader
    PromptFilterService --> OkfReader

    SourceResolver -.->|reads only, never writes| TargetRepo
    JavaAstChunker -.->|reads only, never writes| TargetRepo
    OkfWriter --> OkfStore
    OkfReader --> OkfStore

    classDef readonly fill:#181818,color:#fff,stroke:#0070d1
    class TargetRepo readonly
```

**What this makes visible that the package graph doesn't:** the target source is only ever touched
by `SourceResolver` and `JavaAstChunker`, both strictly read-only (dotted lines) — no arrow points
*into* `TargetRepo`. Every write in the system goes to `OkfStore` or `FileHashStore`, both of which
this application owns and created itself. That's the "no source code changes" constraint (Architecture
Plan §2) made structurally visible rather than just asserted in a doc comment.

# Workflows

## 0. Source resolution (runs before workflow 1, on every index trigger)

```
Caller provides a source path — directory, .zip, or single .java file
        │
        ▼
SourceResolver.resolve(path)
        │
   ┌────┼────────────────┬──────────────────┐
   ▼    ▼                ▼                  ▼
 is a           is a .zip file       is a .java file    anything else
directory?                                              (missing path,
   │                │                     │              wrong extension, etc.)
  YES              YES                   YES                  │
   │                │                     │                   ▼
   │        extract to fresh         effectiveRoot =    reject: throw
   │        temp dir, skipping       file's parent      IllegalArgumentException,
   │        any entry that would     dir; javaFiles =   caller sees a clear
   │        escape it (zip-slip      just that one      failure, not a silent
   │        guard)                   file                empty result
   │                │                     │
   ▼                ▼                     ▼
walk dir for   walk temp dir for    ResolvedSource
*.java files   *.java files          { type: FILE }
   │                │
   ▼                ▼
ResolvedSource   ResolvedSource
{ type:          { type: ZIP,
  DIRECTORY }      temporary: true }
        │
        ▼
  Same downstream flow regardless of type: ParallelIndexer (workflow 1)
  or IncrementalIndexService (workflow 2) just get a List<Path> + an
  effectiveRoot to compute relative paths against — they don't branch
  on source type at all.
        │
        ▼ (ZIP only)
  after indexing completes, SourceResolver.cleanupIfTemporary() deletes
  the temp extraction directory
```

## 1. First-time full index

```
User/operator → POST /mcp/code/reindex_all  (optionally with {"sourcePath": "..."})
                       │
                       ▼
              ParallelIndexer.indexSource(path)
                       │
        SourceResolver.resolve(path)  — see workflow 0 above
                       │
        (resolved to a List<Path> of .java files + an effectiveRoot)
                       │
        ┌──────────────┼──────────────┐
        ▼              ▼              ▼
    Worker 1        Worker 2   ...  Worker N     (pool size = CPU cores, §12)
    (files 1..k)   (files k+1..2k)  (remaining)
        │              │              │
   JavaAstChunker.chunk()  — per file, per worker, no coordination needed
        │              │              │
   OkfWriter.write()   — one .md file per chunk, hash-keyed filename
        │              │              │
   on failure: retry up to codeview.max-retries, isolated to that file/worker
        │              │              │
        └──────────────┴──────────────┘
                       │
                       ▼
          IndexRunResult { succeededFiles, failedFiles, chunksWritten }
                       │
                       ▼
              returned to caller — failures reported, never silently dropped
```

## 2. Incremental re-index on file save

```
Developer saves a .java file
        │
        ▼
FileWatcherService (Java WatchService, watching codeview.repo-root)
        │
        ▼
IncrementalIndexService.reindexFile(file)
        │
        ▼
MerkleHasher.hashContent(new file content)
        │
        ▼
FileHashStore.hasChanged(filePath, newHash)?
        │
   ┌────┴────┐
   NO         YES
   │           │
 skip     JavaAstChunker.chunk() → OkfWriter.write() for each chunk
   │           │
   │      FileHashStore.update(filePath, newHash)
   │           │
   └─────┬─────┘
         ▼
   IndexRunResult returned (success or failure for this one file)
```

Same flow applies to a manual `POST /mcp/code/update_file` call — it's the on-demand version of
this same path, minus the file-watcher trigger.

## 3. Prompt-time context filter (the core feature)

```
Caller (agent/editor/script) has a prompt it's about to send to an LLM
        │
        ▼
POST /mcp/code/prompt_filter  { "prompt": "..." }
        │
        ▼
PromptFilterService.filter(prompt)
        │
        ▼
Step 2: StructuralMatcher — does the prompt name an actual file/class/method?
        │
   ┌────┴────┐
  YES         NO
   │           │
   │      Step 3: KeywordMatcher — do prompt terms match chunk name/path/tags?
   │           │
   │      ┌────┴────┐
   │     YES         NO
   │      │           │
   └──┬───┘           ▼
      ▼          PromptFilterResult.empty()
Step 4: LinkGraphWalker    { noMatch: true, matchedChunks: [], linkedChunks: [] }
  — one-hop walk of              │
    dependency links              │
      │                           │
      ▼                           │
PromptFilterResult                │
 { noMatch: false,                │
   matchStrategy: "structural"    │
     or "keyword",                │
   matchedChunks, linkedChunks }  │
      │                           │
      └─────────────┬─────────────┘
                     ▼
        Response returned to caller
                     │
                     ▼
   Caller attaches matchedChunks + linkedChunks to the original prompt
   and makes its OWN call to an LLM — CodeView does not call an LLM here
   or anywhere else in this system.
```

## 4. Fault handling during first-time index (detail on step 1)

```
Worker picks up file F
        │
        ▼
Attempt 1: chunk(F) → write OKF files
        │
   success? ──YES──▶ report success, move to next file
        │
        NO
        ▼
Attempt 2 (up to codeview.max-retries)
        │
   success? ──YES──▶ report success
        │
        NO
        ▼
Attempts exhausted → report failure for F with reason + attempt count
   (other workers' files are entirely unaffected by this failure)
```
## 5. Developer UI — tree and flow pages

```
Developer opens /ui/tree                    Developer opens /ui/flow
        │                                            │
        ▼                                            ▼
Thymeleaf renders empty page shell          Thymeleaf renders empty page shell
        │                                            │
        ▼                                            ▼
tree.js fetches GET /ui/api/tree            flow.js fetches GET /ui/api/flow
        │                                            │
        ▼                                            ▼
TreeService.buildTree()                     FlowGraphService.buildGraph()
  reads all OKF chunks,                       reads all OKF chunks, matches
  groups by path into                         each chunk's dependencies
  directory/file/symbol                       against other chunks' names/
  nodes                                       paths (same logic as
        │                                     LinkGraphWalker, §11 — just
        ▼                                     whole-graph instead of one-hop)
Rendered as nested <ul>,                            │
click a symbol to fetch                             ▼
GET /mcp/code/get_chunk/{id}                Rendered with vis-network:
and show its source                         one node per chunk, one
        │                                    edge per dependency link
        ▼                                            │
Chunk text shown in the                             ▼
page's chunk panel                          Interactive graph in the browser
```

No step in either flow calls an LLM, a ranking function, or an embedding model — both pages are
reading and re-shaping the same deterministic OKF data the MCP endpoints serve.

## 6. Browser upload (zip or folder)

```
Developer opens /ui/upload
        │
   ┌────┴────┐
   ▼         ▼
Zip form   Folder form (webkitdirectory picker)
   │         │
   ▼         ▼
POST /mcp/code/upload/zip     POST /mcp/code/upload/folder
   │         │
   ▼         ▼
UploadService.indexUploadedZip()   UploadService.indexUploadedFolder()
   │                                   │
save MultipartFile to a          for each file: keep only .java,
temp .zip file                   reject any relative path that would
   │                              escape the reconstruction dir
   ▼                                   │
ParallelIndexer.indexSource(           ▼
  tempZip.toString())            write remaining files to a fresh
   │                              temp dir at their relative paths
   ▼                                   │
SourceResolver detects .zip            ▼
extension → extracts →           ParallelIndexer.indexSource(
same flow as workflow 0/1          tempDir.toString())
   │                                   │
   ▼                                   ▼
IndexRunResult returned to      SourceResolver detects a directory →
the browser, temp .zip file      walks it for .java files → same
deleted either way                flow as workflow 0/1
                                       │
                                       ▼
                                 IndexRunResult returned to the
                                 browser, temp folder deleted
                                 either way (SourceResolver.
                                 deleteRecursively)
```

Both paths converge on the exact same `ParallelIndexer.indexSource()` call as
`POST /mcp/code/reindex_all` (workflow 1) — uploading is a different way of *supplying* a source,
not a different indexing implementation.


# Workflows

## 1. First-time full index

```
User/operator → POST /mcp/code/reindex_all
                       │
                       ▼
              ParallelIndexer.indexAll()
                       │
        walk codeview.repo-root for *.java files
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

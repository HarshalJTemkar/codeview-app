package com.codeview.app.index;

import com.codeview.app.config.CodeViewProperties;
import com.codeview.app.source.ResolvedSource;
import com.codeview.app.source.SourceResolver;
import com.codeview.app.source.SourceType;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Watches the target source for file saves (Architecture Plan §5's trigger
 * workflow: "FileWatcher -> file modified event -> Chunker") and calls
 * IncrementalIndexService for the changed file only. Read-only against the
 * target source: this only ever reads file events and file content, never
 * writes to it.
 *
 * Only makes sense for a DIRECTORY-configured source — watching a .zip file
 * for changes wouldn't reflect its extracted contents automatically, and
 * watching a single FILE source is watching that one file's parent
 * directory but filtering to just that file. Both non-directory cases are
 * handled explicitly below rather than silently doing nothing.
 */
@Service
public class FileWatcherService {

    private static final Logger log = LoggerFactory.getLogger(FileWatcherService.class);

    private final CodeViewProperties props;
    private final IncrementalIndexService incrementalIndexService;
    private final SourceResolver sourceResolver;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread watchThread;

    public FileWatcherService(CodeViewProperties props, IncrementalIndexService incrementalIndexService,
                               SourceResolver sourceResolver) {
        this.props = props;
        this.incrementalIndexService = incrementalIndexService;
        this.sourceResolver = sourceResolver;
    }

    @PostConstruct
    public void start() {
        if (!props.isWatchEnabled()) {
            log.info("File watcher disabled via codeview.watch-enabled=false.");
            return;
        }

        ResolvedSource resolved;
        try {
            resolved = sourceResolver.resolve(props.getRepoRoot());
        } catch (Exception e) {
            log.warn("File watcher not started: could not resolve codeview.repo-root ({}): {}",
                    props.getRepoRoot(), e.getMessage());
            return;
        }

        if (resolved.type() == SourceType.ZIP) {
            log.info("codeview.repo-root is a .zip file — file watching doesn't apply to it. " +
                    "Re-run POST /mcp/code/reindex_all after updating the zip instead.");
            return;
        }

        Path watchRoot = resolved.effectiveRoot();
        String singleFileFilter = resolved.type() == SourceType.FILE
                ? resolved.javaFiles().get(0).getFileName().toString()
                : null;

        running.set(true);
        watchThread = new Thread(() -> watchLoop(watchRoot, singleFileFilter), "codeview-file-watcher");
        watchThread.setDaemon(true);
        watchThread.start();
        log.info("File watcher started on {}{}", watchRoot,
                singleFileFilter != null ? " (filtering to " + singleFileFilter + ")" : "");
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (watchThread != null) {
            watchThread.interrupt();
        }
    }

    private void watchLoop(Path repoRoot, String singleFileFilter) {
        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            registerAll(repoRoot, watchService);

            while (running.get()) {
                WatchKey key;
                try {
                    key = watchService.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (key == null) {
                    continue;
                }

                Path dir = (Path) key.watchable();
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }
                    Path changed = dir.resolve((Path) event.context());

                    boolean matchesSingleFileFilter =
                            singleFileFilter == null || changed.getFileName().toString().equals(singleFileFilter);

                    if (changed.toString().endsWith(".java") && matchesSingleFileFilter
                            && Files.isRegularFile(changed)) {
                        String relativePath = repoRoot.relativize(changed).toString().replace('\\', '/');
                        incrementalIndexService.reindexFile(repoRoot, changed, relativePath);
                    }
                }
                key.reset();
            }
        } catch (IOException e) {
            log.error("File watcher failed to start: {}", e.getMessage());
        }
    }

    private void registerAll(Path root, WatchService watchService) throws IOException {
        Files.walk(root)
                .filter(Files::isDirectory)
                .forEach(dir -> {
                    try {
                        dir.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY,
                                StandardWatchEventKinds.ENTRY_CREATE);
                    } catch (IOException e) {
                        log.warn("Could not register watcher on {}: {}", dir, e.getMessage());
                    }
                });
    }
}


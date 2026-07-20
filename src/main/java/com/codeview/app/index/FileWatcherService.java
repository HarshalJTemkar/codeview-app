package com.codeview.app.index;

import com.codeview.app.config.CodeViewProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Watches the target repo for file saves (Architecture Plan §5's trigger
 * workflow: "FileWatcher -> file modified event -> Chunker") and calls
 * IncrementalIndexService for the changed file only. Read-only against the
 * target repo: this only ever reads file events and file content, never
 * writes to the target repo.
 */
@Service
public class FileWatcherService {

    private static final Logger log = LoggerFactory.getLogger(FileWatcherService.class);

    private final CodeViewProperties props;
    private final IncrementalIndexService incrementalIndexService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread watchThread;

    public FileWatcherService(CodeViewProperties props, IncrementalIndexService incrementalIndexService) {
        this.props = props;
        this.incrementalIndexService = incrementalIndexService;
    }

    @PostConstruct
    public void start() {
        if (!props.isWatchEnabled()) {
            log.info("File watcher disabled via codeview.watch-enabled=false.");
            return;
        }
        Path repoRoot = Path.of(props.getRepoRoot());
        if (!Files.isDirectory(repoRoot)) {
            log.warn("Repo root {} does not exist; file watcher not started.", repoRoot);
            return;
        }

        running.set(true);
        watchThread = new Thread(() -> watchLoop(repoRoot), "codeview-file-watcher");
        watchThread.setDaemon(true);
        watchThread.start();
        log.info("File watcher started on {}", repoRoot);
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (watchThread != null) {
            watchThread.interrupt();
        }
    }

    private void watchLoop(Path repoRoot) {
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
                    if (changed.toString().endsWith(".java") && Files.isRegularFile(changed)) {
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

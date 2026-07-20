package com.codeview.app.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Worker pool for first-time parallel indexing (Architecture Plan §12, confirmed
 * approach): scales to available CPU cores at runtime, with a configurable
 * override/cap via codeview.worker-pool-size, and a floor of 1 worker so it
 * never fails to start on single-core hardware.
 */
@Configuration
@EnableConfigurationProperties(CodeViewProperties.class)
public class ExecutorConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService indexingExecutor(CodeViewProperties props) {
        int configured = props.getWorkerPoolSize();
        int poolSize = configured > 0
                ? configured
                : Math.max(1, Runtime.getRuntime().availableProcessors());
        return Executors.newFixedThreadPool(poolSize);
    }
}

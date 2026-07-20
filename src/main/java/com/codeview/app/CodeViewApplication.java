package com.codeview.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * CodeView: read-only, zero-token, no-auth code intelligence server.
 *
 * No security starter is on the classpath and no auth filter is registered
 * anywhere in this application — this is intentional, per the "no auth"
 * requirement, not an oversight. See docs/Architecture.md for the tradeoff
 * this creates and what deployment context it assumes.
 */
@SpringBootApplication
public class CodeViewApplication {
    public static void main(String[] args) {
        SpringApplication.run(CodeViewApplication.class, args);
    }
}

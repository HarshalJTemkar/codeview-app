package com.codeview.app.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Metadata for the generated OpenAPI spec (served at /v3/api-docs, browsable
 * at /swagger-ui.html). No security scheme is declared here — consistent
 * with NFR-1 (no auth anywhere in this application).
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI codeViewOpenApi() {
        return new OpenAPI().info(new Info()
                .title("CodeView MCP API")
                .version("1.0.0")
                .description("Read-only, zero-token, no-auth code intelligence API. "
                        + "search/get_chunk/tree/update_file/reindex_all are structural — "
                        + "no ranking, embedding, or LLM call anywhere. prompt_filter assembles "
                        + "context for the CALLER's own LLM request; this API never makes that "
                        + "call itself. See docs/Architecture.md in the project repo for the full design."));
    }
}

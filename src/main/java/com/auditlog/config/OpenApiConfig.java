package com.auditlog.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI auditLogOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Audit Log Service")
                        .version("0.0.1")
                        .description("Tamper-evident, append-only audit log API"));
    }
}

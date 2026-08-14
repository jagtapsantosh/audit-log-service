package com.auditlog.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SecurityHandlerConfig {

    @Bean
    JsonAuthHandlers jsonAuthHandlers(ObjectMapper objectMapper) {
        return new JsonAuthHandlers(objectMapper);
    }
}

package com.auditlog.config.security;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
public class ProductionSecurityGuard implements ApplicationRunner {

    private final SecurityProperties properties;

    public ProductionSecurityGuard(SecurityProperties properties) {
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        ProductionSecrets.assertSafe(
                properties.pepper(),
                properties.jwt() == null ? null : properties.jwt().secret(),
                properties.exportSigningKey());
    }
}

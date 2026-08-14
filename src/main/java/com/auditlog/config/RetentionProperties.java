package com.auditlog.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Retention policy configuration.
 *
 * <p>The window is measured against {@code recorded_at} (server ingest time), so a producer cannot
 * keep a record hot by backdating {@code occurredAt}.
 */
@Validated
@ConfigurationProperties(prefix = "audit.retention")
public record RetentionProperties(

        /** Records ingested longer ago than this are archivable. Zero archives everything. */
        @DefaultValue("365") @Min(0) int days,

        @DefaultValue Sweep sweep
) {

    public record Sweep(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("0 30 3 * * *") @NotBlank String cron
    ) {}
}

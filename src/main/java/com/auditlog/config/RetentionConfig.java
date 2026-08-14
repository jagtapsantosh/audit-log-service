package com.auditlog.config;

import com.auditlog.domain.RetentionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Wiring for the retention policy: bind {@code audit.retention.*} and run the sweep on a schedule.
 *
 * <p>The scheduled job does exactly what {@code POST /audit/admin/archive} does, so an operator can
 * always trigger it by hand. It can be turned off with
 * {@code audit.retention.sweep.enabled=false} where an external scheduler owns the cadence.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(RetentionProperties.class)
public class RetentionConfig {

    @Component
    @ConditionalOnProperty(prefix = "audit.retention.sweep", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    static class RetentionSweepJob {

        private final RetentionService retentionService;

        RetentionSweepJob(RetentionService retentionService) {
            this.retentionService = retentionService;
        }

        @Scheduled(cron = "${audit.retention.sweep.cron:0 30 3 * * *}", zone = "UTC")
        void sweep() {
            retentionService.sweep();
        }
    }
}

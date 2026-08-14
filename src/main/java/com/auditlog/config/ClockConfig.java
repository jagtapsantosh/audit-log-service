package com.auditlog.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /** Injected rather than calling {@code Instant.now()} so ingest time is testable. */
    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }
}

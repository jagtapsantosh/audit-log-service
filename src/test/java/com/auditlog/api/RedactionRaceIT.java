package com.auditlog.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class RedactionRaceIT extends AuditApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void concurrentRedactionOfTheSamePathLeavesOneOverlayRow() throws Exception {
        long recordId = appendEvent("ACCOUNT_VIEWED", "user-123", "CLIENT_ACCOUNT", "acct-1",
                "2026-08-14T11:30:00Z", "{\"accountNumber\":\"1234\",\"ip\":\"10.0.0.1\"}")
                .get("id").asLong();
        String token = adminToken();

        int writers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        for (int i = 0; i < writers; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    MvcResult result = mockMvc.perform(post("/audit/events/{id}/redact", recordId)
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"fieldPaths\":[\"accountNumber\"],\"reason\":\"GDPR\"}"))
                            .andReturn();
                    int status = result.getResponse().getStatus();
                    if (status == 200) {
                        ok.incrementAndGet();
                    } else if (status == 409) {
                        conflict.incrementAndGet();
                    } else {
                        other.incrementAndGet();
                    }
                } catch (Exception e) {
                    other.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(other.get()).isZero();
        assertThat(ok.get()).isGreaterThanOrEqualTo(1);
        assertThat(ok.get() + conflict.get()).isEqualTo(writers);
        Integer overlayRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_redactions WHERE audit_record_id = ?",
                Integer.class,
                recordId);
        assertThat(overlayRows).isEqualTo(1);
        assertThat(verifyChain().get("intact").asBoolean()).isTrue();
    }
}

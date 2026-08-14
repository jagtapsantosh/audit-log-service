package com.auditlog.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Concurrency guardrail. Uses a real servlet container and real parallel HTTP requests, because the
 * race this protects against (two writers reading the same chain head) only appears with genuinely
 * simultaneous transactions.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class ConcurrentAppendIT {

    private static final int WRITERS = 10;
    private static final String INGEST_KEY = "als_ingest_dev_key_do_not_use_in_prod";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    @DisplayName("parallel appends produce a contiguous, intact chain")
    void parallelAppendsDoNotForkTheChain() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(WRITERS);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> submissions = new ArrayList<>();

        try {
            for (int i = 0; i < WRITERS; i++) {
                int writer = i;
                submissions.add(pool.submit(() -> {
                    startGate.await();
                    return append(writer);
                }));
            }
            startGate.countDown();

            List<Long> sequences = new ArrayList<>();
            for (Future<ResponseEntity<String>> submission : submissions) {
                ResponseEntity<String> response = submission.get(30, TimeUnit.SECONDS);
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                sequences.add(objectMapper.readTree(response.getBody()).get("sequence").asLong());
            }

            assertThat(sequences).doesNotHaveDuplicates().hasSize(WRITERS);
            assertThat(sequences.stream().sorted().toList())
                    .isEqualTo(LongStream.rangeClosed(1, WRITERS).boxed().toList());
        } finally {
            pool.shutdownNow();
        }

        JsonNode verification = verify();
        assertThat(verification.get("intact").asBoolean()).isTrue();
        assertThat(verification.get("totalRecords").asLong()).isEqualTo(WRITERS);
    }

    private ResponseEntity<String> append(int writer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-API-Key", INGEST_KEY);
        String body = """
                {"eventType":"USER_LOGIN","actorId":"user-%d","resourceType":"SESSION",\
                "resourceId":"sess-%d","occurredAt":"2026-08-14T11:30:00Z","payload":{"writer":%d}}"""
                .formatted(writer, writer, writer);
        return rest.exchange("/audit/events", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode verify() throws Exception {
        HttpHeaders tokenHeaders = new HttpHeaders();
        tokenHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> token = rest.exchange(
                "/auth/token",
                HttpMethod.POST,
                new HttpEntity<>("""
                        {"client_id":"ops-admin","client_secret":"ops-admin-secret-dev","scope":"audit.read"}
                        """, tokenHeaders),
                String.class);
        assertThat(token.getStatusCode()).isEqualTo(HttpStatus.OK);

        HttpHeaders verifyHeaders = new HttpHeaders();
        verifyHeaders.setBearerAuth(objectMapper.readTree(token.getBody()).get("access_token").asText());
        ResponseEntity<String> response = rest.exchange(
                "/audit/verify", HttpMethod.GET, new HttpEntity<>(verifyHeaders), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody());
    }
}

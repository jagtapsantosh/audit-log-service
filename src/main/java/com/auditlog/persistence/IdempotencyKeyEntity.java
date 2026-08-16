package com.auditlog.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "audit_idempotency_keys")
public class IdempotencyKeyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, updatable = false, length = 255)
    private String clientId;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
    private String idempotencyKey;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    @Column(name = "audit_record_id", nullable = false, updatable = false)
    private long auditRecordId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected IdempotencyKeyEntity() {
    }

    public IdempotencyKeyEntity(
            String clientId,
            String idempotencyKey,
            String requestHash,
            long auditRecordId,
            Instant createdAt
    ) {
        this.clientId = clientId;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.auditRecordId = auditRecordId;
        this.createdAt = createdAt;
    }

    public String getClientId() {
        return clientId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public long getAuditRecordId() {
        return auditRecordId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

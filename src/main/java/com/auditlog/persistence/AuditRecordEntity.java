package com.auditlog.persistence;

import com.auditlog.domain.RecordStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Row mapping for {@code audit_records}.
 *
 * <p>Every hashed column is {@code updatable = false} and the class has no setters at all, so
 * Hibernate can only ever issue an INSERT for this entity. Rewriting a stored record requires going
 * around the application to raw SQL, which is exactly what chain verification is designed to detect.
 *
 * <p>Scenario B adds three columns that are not part of the hash pre-image — {@code status},
 * {@code archived_at}, {@code has_redactions}. They are still not settable here: retention and
 * redaction change them through the narrowly scoped bulk statements in
 * {@link AuditRecordRepository}, so there is no code path that can load a record and mutate it.
 */
@Entity
@Table(name = "audit_records")
public class AuditRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "sequence_num", nullable = false, updatable = false, unique = true)
    private long sequenceNum;

    @Column(name = "event_type", nullable = false, updatable = false, length = 100)
    private String eventType;

    @Column(name = "actor_id", nullable = false, updatable = false, length = 255)
    private String actorId;

    @Column(name = "resource_type", nullable = false, updatable = false, length = 100)
    private String resourceType;

    @Column(name = "resource_id", nullable = false, updatable = false, length = 255)
    private String resourceId;

    /** Canonical JSON text, stored as {@code jsonb} so it stays queryable. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "content_hash", nullable = false, updatable = false, length = 64)
    private String contentHash;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "previous_hash", nullable = false, updatable = false, length = 64)
    private String previousHash;

    /** Retention state. Outside the hash, so archiving cannot break verification. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RecordStatus status = RecordStatus.ACTIVE;

    @Column(name = "archived_at")
    private Instant archivedAt;

    /** Cache of "this record has overlay rows", so reads can skip the join when false. */
    @Column(name = "has_redactions", nullable = false)
    private boolean hasRedactions;

    protected AuditRecordEntity() {
        // for Hibernate
    }

    public AuditRecordEntity(
            long sequenceNum,
            String eventType,
            String actorId,
            String resourceType,
            String resourceId,
            String payload,
            Instant occurredAt,
            Instant recordedAt,
            String contentHash,
            String previousHash
    ) {
        this.sequenceNum = sequenceNum;
        this.eventType = eventType;
        this.actorId = actorId;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.payload = payload;
        this.occurredAt = occurredAt;
        this.recordedAt = recordedAt;
        this.contentHash = contentHash;
        this.previousHash = previousHash;
    }

    public Long getId() {
        return id;
    }

    public long getSequenceNum() {
        return sequenceNum;
    }

    public String getEventType() {
        return eventType;
    }

    public String getActorId() {
        return actorId;
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public String getContentHash() {
        return contentHash;
    }

    public String getPreviousHash() {
        return previousHash;
    }

    public RecordStatus getStatus() {
        return status;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public boolean isHasRedactions() {
        return hasRedactions;
    }
}

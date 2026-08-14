package com.auditlog.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Row mapping for {@code audit_redactions}: one masked field path on one record.
 *
 * <p>Append-only like the log. Every column is {@code updatable = false} and there are no setters, so
 * a redaction can be added but never quietly rewritten or backdated. The foreign key is mapped as a
 * plain id rather than an association, because nothing here needs to navigate to the record.
 */
@Entity
@Table(name = "audit_redactions")
public class AuditRedactionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "audit_record_id", nullable = false, updatable = false)
    private long auditRecordId;

    @Column(name = "field_path", nullable = false, updatable = false, length = 255)
    private String fieldPath;

    @Column(name = "redacted_at", nullable = false, updatable = false)
    private Instant redactedAt;

    @Column(name = "redacted_by", nullable = false, updatable = false, length = 255)
    private String redactedBy;

    @Column(name = "reason", updatable = false, length = 500)
    private String reason;

    protected AuditRedactionEntity() {
        // for Hibernate
    }

    public AuditRedactionEntity(
            long auditRecordId,
            String fieldPath,
            Instant redactedAt,
            String redactedBy,
            String reason
    ) {
        this.auditRecordId = auditRecordId;
        this.fieldPath = fieldPath;
        this.redactedAt = redactedAt;
        this.redactedBy = redactedBy;
        this.reason = reason;
    }

    public Long getId() {
        return id;
    }

    public long getAuditRecordId() {
        return auditRecordId;
    }

    public String getFieldPath() {
        return fieldPath;
    }

    public Instant getRedactedAt() {
        return redactedAt;
    }

    public String getRedactedBy() {
        return redactedBy;
    }

    public String getReason() {
        return reason;
    }
}

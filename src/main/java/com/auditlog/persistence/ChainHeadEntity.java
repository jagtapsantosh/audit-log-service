package com.auditlog.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "audit_chain_head")
public class ChainHeadEntity {

    @Id
    @Column(name = "id", nullable = false)
    private short id = 1;

    @Column(name = "sequence_num", nullable = false)
    private long sequenceNum;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ChainHeadEntity() {
    }

    public ChainHeadEntity(long sequenceNum, String contentHash, Instant updatedAt) {
        this.id = 1;
        this.sequenceNum = sequenceNum;
        this.contentHash = contentHash;
        this.updatedAt = updatedAt;
    }

    public void replace(long sequenceNum, String contentHash, Instant updatedAt) {
        this.sequenceNum = sequenceNum;
        this.contentHash = contentHash;
        this.updatedAt = updatedAt;
    }

    public long getSequenceNum() {
        return sequenceNum;
    }

    public String getContentHash() {
        return contentHash;
    }
}

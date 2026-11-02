package com.bhuvan.ledger.entity;

import com.bhuvan.ledger.util.Timestamps;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "transactions")
@Immutable
@Getter
public class LedgerTransaction {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "txn_type", nullable = false, length = 12)
    private TransactionType txnType;

    @Column(name = "amount", nullable = false)
    private long amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 64)
    private String idempotencyKey;

    @Column(name = "reverses_txn_id")
    private UUID reversesTxnId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerTransaction() {
    }

    @Builder
    public LedgerTransaction(UUID id, TransactionType txnType, long amount, String currency,
            String description, String idempotencyKey, UUID reversesTxnId) {
        this.id = id;
        this.txnType = txnType;
        this.amount = amount;
        this.currency = currency;
        this.description = description;
        this.idempotencyKey = idempotencyKey;
        this.reversesTxnId = reversesTxnId;
        this.createdAt = Timestamps.now();
    }
}

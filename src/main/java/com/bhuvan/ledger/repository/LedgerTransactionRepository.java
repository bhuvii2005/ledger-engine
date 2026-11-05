package com.bhuvan.ledger.repository;

import com.bhuvan.ledger.entity.LedgerTransaction;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, UUID> {

    // Row lock on the original before reversing it, so two reversals of the same transaction
    // queue here and the second one sees the first one's REVERSAL row. Locking fires no
    // UPDATE trigger, so the append-only rule is untouched.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from LedgerTransaction t where t.id = :id")
    Optional<LedgerTransaction> lockById(@Param("id") UUID id);

    boolean existsByReversesTxnId(UUID reversesTxnId);
}

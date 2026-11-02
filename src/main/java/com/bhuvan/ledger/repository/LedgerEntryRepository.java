package com.bhuvan.ledger.repository;

import com.bhuvan.ledger.entity.LedgerEntry;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    @Query("select e from LedgerEntry e where e.accountId = :accountId and e.id < :beforeId order by e.id desc")
    List<LedgerEntry> findPage(@Param("accountId") Long accountId, @Param("beforeId") Long beforeId,
            Pageable pageable); // Pageable only for limit

    List<LedgerEntry> findByTransactionIdOrderByIdAsc(UUID transactionId);
}

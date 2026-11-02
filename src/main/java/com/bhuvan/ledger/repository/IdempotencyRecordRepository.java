package com.bhuvan.ledger.repository;

import com.bhuvan.ledger.entity.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, String> {

    // Returns 1 if we now own the key, 0 if it already exists. If another open transaction
    // inserted the same key, Postgres blocks here until that one commits (-> 0) or rolls back (-> 1).
    @Modifying
    @Query(value = """
            insert into idempotency_keys (idem_key, request_hash, operation, status, created_at, updated_at)
            values (:key, :hash, :operation, 'IN_PROGRESS', now(), now())
            on conflict (idem_key) do nothing
            """, nativeQuery = true)
    int claim(@Param("key") String key, @Param("hash") String hash, @Param("operation") String operation);
}

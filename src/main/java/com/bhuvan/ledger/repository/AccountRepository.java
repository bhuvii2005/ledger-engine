package com.bhuvan.ledger.repository;

import com.bhuvan.ledger.entity.Account;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, Long> {

    // Returns only the id. Deliberately NOT the entity, so nothing stale sits
    // in the persistence context before we take the lock.
    @Query("select a.id from Account a where a.accountNumber = :number")
    Optional<Long> findIdByAccountNumber(@Param("number") String number);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> lockById(@Param("id") Long id);

    Optional<Account> findByAccountNumber(String number); // read-only endpoints
}

package com.bhuvan.ledger.service;

import com.bhuvan.ledger.exception.ErrorCode;
import com.bhuvan.ledger.exception.LedgerException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Entry point for every money movement. Deliberately NOT @Transactional: each call to
 * PostingService.post is its own DB transaction, so when it returns the work has
 * committed, and when it throws a lock error the transaction is already rolled back
 * and a retry starts clean.
 */
@Slf4j
@Service
public class TransactionFacade {

    private final PostingService postingService;
    private final IdempotencyCache idempotencyCache;
    private final Counter lockRetries;
    private final int maxAttempts;
    private final long backoffBaseMs;

    public TransactionFacade(PostingService postingService,
            IdempotencyCache idempotencyCache,
            MeterRegistry meterRegistry,
            @Value("${ledger.posting.max-attempts}") int maxAttempts,
            @Value("${ledger.posting.retry-backoff-ms}") long backoffBaseMs) {
        this.postingService = postingService;
        this.idempotencyCache = idempotencyCache;
        this.lockRetries = meterRegistry.counter("ledger.lock.retries");
        this.maxAttempts = maxAttempts;
        this.backoffBaseMs = backoffBaseMs;
    }

    public PostingResult execute(PostingCommand cmd) {
        Optional<PostingResult> cached = idempotencyCache.lookup(cmd);
        if (cached.isPresent()) {
            return cached.get();
        }

        Optional<String> marker = idempotencyCache.tryMarkInProgress(cmd);
        if (marker.isEmpty()) {
            // Either another request with this key marked it between our lookup and SET NX
            // (look again: 409 or a replay), or Redis is unavailable (lookup is a miss again
            // and we go to Postgres, which serializes duplicates on the key's unique index).
            cached = idempotencyCache.lookup(cmd);
            if (cached.isPresent()) {
                return cached.get();
            }
        }
        try {
            PostingResult result = postWithRetry(cmd);
            idempotencyCache.storeCompleted(cmd, result); // the DB transaction has committed by now
            return result;
        } catch (RuntimeException e) {
            // this request committed nothing, so let the next attempt in straight away
            marker.ifPresent(m -> idempotencyCache.clearInProgress(cmd, m));
            throw e;
        }
    }

    private PostingResult postWithRetry(PostingCommand cmd) {
        for (int attempt = 1; ; attempt++) {
            try {
                return postingService.post(cmd);
            } catch (PessimisticLockingFailureException e) {
                // deadlock victim (40P01) or lock_timeout (55P03); statement_timeout is a
                // different exception and is deliberately not retried
                if (attempt >= maxAttempts) {
                    log.warn("Giving up on {} key={} after {} attempts: {}", cmd.operation(),
                            cmd.idempotencyKey(), attempt, e.getClass().getSimpleName());
                    throw new LedgerException(ErrorCode.SYSTEM_BUSY,
                            "The system is busy, please retry shortly", e);
                }
                lockRetries.increment();
                log.warn("Lock failure on {} key={} attempt {}/{}: {}", cmd.operation(),
                        cmd.idempotencyKey(), attempt, maxAttempts, e.getClass().getSimpleName());
                sleepBeforeRetry(attempt);
            }
        }
    }

    private void sleepBeforeRetry(int attempt) {
        // exponential backoff with jitter so colliding requests don't retry in lockstep
        long delayMs = backoffBaseMs * (1L << (attempt - 1)) + ThreadLocalRandom.current().nextLong(26);
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LedgerException(ErrorCode.SYSTEM_BUSY, "Interrupted while waiting to retry", e);
        }
    }
}

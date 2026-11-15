package com.bhuvan.ledger.service;

import com.bhuvan.ledger.entity.TransactionType;
import com.bhuvan.ledger.exception.ErrorCode;
import com.bhuvan.ledger.exception.LedgerException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
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

    // Postgres' default name for UNIQUE (reverses_txn_id) on transactions
    static final String ONE_REVERSAL_PER_TRANSACTION = "transactions_reverses_txn_id_key";

    static final String MDC_IDEMPOTENCY_KEY = "idempotencyKey";

    private final PostingService postingService;
    private final IdempotencyCache idempotencyCache;
    private final MeterRegistry meterRegistry;
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
        this.meterRegistry = meterRegistry;
        this.lockRetries = meterRegistry.counter("ledger.lock.retries");
        this.maxAttempts = maxAttempts;
        this.backoffBaseMs = backoffBaseMs;
    }

    public PostingResult execute(PostingCommand cmd) {
        MDC.put(MDC_IDEMPOTENCY_KEY, cmd.idempotencyKey());
        try {
            PostingResult result = executeOnce(cmd);
            countPosting(cmd, result.replayed() ? "replayed" : result.isSuccess() ? "created" : "rejected");
            return result;
        } catch (LedgerException e) {
            // 409 in progress, 422 key reused, 409 already reversed: refused without being stored.
            // SYSTEM_BUSY (503) is the database giving up, not a refusal.
            countPosting(cmd, e.getCode().status().is4xxClientError() ? "conflict" : "error");
            throw e;
        } catch (RuntimeException e) {
            countPosting(cmd, "error");
            throw e;
        } finally {
            MDC.remove(MDC_IDEMPOTENCY_KEY);
        }
    }

    private PostingResult executeOnce(PostingCommand cmd) {
        Optional<PostingResult> cached = idempotencyCache.lookup(cmd);
        if (cached.isPresent()) {
            countReplay("redis");
            return cached.get();
        }

        Optional<String> marker = idempotencyCache.tryMarkInProgress(cmd);
        if (marker.isEmpty()) {
            // Either another request with this key marked it between our lookup and SET NX
            // (look again: 409 or a replay), or Redis is unavailable (lookup is a miss again
            // and we go to Postgres, which serializes duplicates on the key's unique index).
            cached = idempotencyCache.lookup(cmd);
            if (cached.isPresent()) {
                countReplay("redis");
                return cached.get();
            }
        }
        try {
            PostingResult result = postWithRetry(cmd);
            if (result.replayed()) {
                // Redis didn't have it (expired, evicted, or down), so Postgres answered
                countReplay("db");
            }
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
            } catch (DataIntegrityViolationException e) {
                if (isSecondReversal(cmd, e)) {
                    // The row lock on the original should make this unreachable. If it fires, the
                    // database still kept the ledger right, but the locking logic is broken.
                    log.error("Second reversal of {} reached the unique constraint (key={}); "
                            + "the row lock did not serialize it", cmd.reversesTxnId(), cmd.idempotencyKey(), e);
                    throw new LedgerException(ErrorCode.ALREADY_REVERSED,
                            "Transaction " + cmd.reversesTxnId() + " has already been reversed", e)
                            .with("transactionId", cmd.reversesTxnId());
                }
                throw e;
            }
        }
    }

    private static boolean isSecondReversal(PostingCommand cmd, DataIntegrityViolationException e) {
        if (cmd.operation() != TransactionType.REVERSAL) {
            return false;
        }
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof ConstraintViolationException cve
                    && ONE_REVERSAL_PER_TRANSACTION.equalsIgnoreCase(cve.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    // result is one of created, rejected, replayed, conflict, error: a small fixed set,
    // so the number of time series stays bounded
    private void countPosting(PostingCommand cmd, String result) {
        Counter.builder("ledger.postings")
                .tag("type", cmd.operation().name())
                .tag("result", result)
                .register(meterRegistry)
                .increment();
    }

    private void countReplay(String source) {
        Counter.builder("ledger.idempotency.replays").tag("source", source).register(meterRegistry).increment();
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

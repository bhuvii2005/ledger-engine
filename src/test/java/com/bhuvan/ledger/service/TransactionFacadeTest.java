package com.bhuvan.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bhuvan.ledger.entity.TransactionType;
import com.bhuvan.ledger.exception.ErrorCode;
import com.bhuvan.ledger.exception.LedgerException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.slf4j.MDC;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;

class TransactionFacadeTest {

    private final PostingService postingService = mock(PostingService.class);
    private final IdempotencyCache cache = mock(IdempotencyCache.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private TransactionFacade facade;

    private final PostingCommand cmd = PostingCommand.of(TransactionType.TRANSFER, "key-12345678",
            "ACC1000000001", "ACC1000000002", 100, "INR", null, null);
    private final PostingResult ok = new PostingResult(201, "{}", UUID.randomUUID(), false);

    @BeforeEach
    void setUp() {
        facade = new TransactionFacade(postingService, cache, registry, 3, 0);
        when(cache.lookup(any())).thenReturn(Optional.empty());
        when(cache.tryMarkInProgress(any())).thenReturn(IdempotencyCache.Claim.claimed("marker"));
    }

    @Test
    void redisReplayIsReturnedWithoutTouchingTheDatabase() {
        PostingResult replay = new PostingResult(201, "{}", UUID.randomUUID(), true);
        when(cache.lookup(cmd)).thenReturn(Optional.of(replay));

        assertThat(facade.execute(cmd)).isEqualTo(replay);
        verifyNoInteractions(postingService);
        verify(cache, never()).tryMarkInProgress(any());
    }

    @Test
    void inProgressInRedisIsA409AndNothingIsPosted() {
        when(cache.lookup(cmd)).thenThrow(new LedgerException(ErrorCode.REQUEST_IN_PROGRESS, "busy"));

        assertThatThrownBy(() -> facade.execute(cmd))
                .extracting(e -> ((LedgerException) e).getCode())
                .isEqualTo(ErrorCode.REQUEST_IN_PROGRESS);
        verifyNoInteractions(postingService);
    }

    @Test
    void marksInProgressThenStoresCompletedOnlyAfterPostReturns() {
        when(postingService.post(cmd)).thenReturn(ok);

        assertThat(facade.execute(cmd)).isEqualTo(ok);
        InOrder order = inOrder(cache, postingService);
        order.verify(cache).lookup(cmd);
        order.verify(cache).tryMarkInProgress(cmd);
        order.verify(postingService).post(cmd);
        order.verify(cache).storeCompleted(cmd, ok);
        verify(cache, never()).clearInProgress(any(), anyString());
    }

    @Test
    void failureClearsOurOwnMarkerAndStoresNothing() {
        when(postingService.post(any())).thenThrow(new IllegalStateException("bug"));

        assertThatThrownBy(() -> facade.execute(cmd)).isInstanceOf(IllegalStateException.class);
        verify(cache).clearInProgress(cmd, "marker");
        verify(cache, never()).storeCompleted(any(), any());
    }

    @Test
    void losingTheSetNxRaceLooksAgainAndReturnsTheWinnersAnswer() {
        when(cache.tryMarkInProgress(cmd)).thenReturn(IdempotencyCache.Claim.TAKEN);
        when(cache.lookup(cmd))
                .thenReturn(Optional.empty())
                .thenThrow(new LedgerException(ErrorCode.REQUEST_IN_PROGRESS, "busy"));

        assertThatThrownBy(() -> facade.execute(cmd))
                .extracting(e -> ((LedgerException) e).getCode())
                .isEqualTo(ErrorCode.REQUEST_IN_PROGRESS);
        verifyNoInteractions(postingService);
    }

    @Test
    void withRedisUnavailableItPostsThroughPostgresAndClearsNothing() {
        // lookup misses and SET NX gives MISS: what IdempotencyCache returns when Redis is down
        when(cache.tryMarkInProgress(cmd)).thenReturn(IdempotencyCache.Claim.MISS);
        when(postingService.post(cmd)).thenThrow(new PessimisticLockingFailureException("lock timeout"));

        assertThatThrownBy(() -> facade.execute(cmd)).isInstanceOf(LedgerException.class);
        verify(cache, never()).clearInProgress(any(), anyString());
        verify(cache, never()).storeCompleted(any(), any());
    }

    @Test
    void aMissDoesNotLookAgainBecauseThatCouldOnlyFindOurOwnMarker() {
        // the second lookup is what turned a lost SET NX reply into a 409 for the request itself
        when(cache.tryMarkInProgress(cmd)).thenReturn(IdempotencyCache.Claim.MISS);
        when(cache.lookup(cmd))
                .thenReturn(Optional.empty())
                .thenThrow(new LedgerException(ErrorCode.REQUEST_IN_PROGRESS, "own marker"));
        when(postingService.post(cmd)).thenReturn(ok);

        assertThat(facade.execute(cmd)).isEqualTo(ok);
        verify(cache, times(1)).lookup(cmd);
        verify(cache).storeCompleted(cmd, ok);
    }

    @Test
    void systemBusyClearsTheMarkerSoTheSameKeyCanBeRetried() {
        when(postingService.post(any())).thenThrow(new PessimisticLockingFailureException("lock timeout"));

        assertThatThrownBy(() -> facade.execute(cmd)).isInstanceOf(LedgerException.class);
        verify(cache).clearInProgress(eq(cmd), eq("marker"));
    }

    @Test
    void lockFailureIsRetriedAndTheRetryCanSucceed() {
        when(postingService.post(any()))
                .thenThrow(new CannotAcquireLockException("deadlock"))
                .thenReturn(ok);

        assertThat(facade.execute(cmd)).isEqualTo(ok);
        verify(postingService, times(2)).post(cmd);
        assertThat(retries()).isEqualTo(1);
    }

    @Test
    void givesUpWithSystemBusyAfterMaxAttempts() {
        when(postingService.post(any())).thenThrow(new PessimisticLockingFailureException("lock timeout"));

        assertThatThrownBy(() -> facade.execute(cmd))
                .isInstanceOf(LedgerException.class)
                .extracting(e -> ((LedgerException) e).getCode())
                .isEqualTo(ErrorCode.SYSTEM_BUSY);
        verify(postingService, times(3)).post(cmd);
        // the counter counts retries actually made: attempts 2 and 3
        assertThat(retries()).isEqualTo(2);
    }

    @Test
    void statementTimeoutIsNotRetried() {
        when(postingService.post(any())).thenThrow(new QueryTimeoutException("statement timeout"));

        assertThatThrownBy(() -> facade.execute(cmd)).isInstanceOf(QueryTimeoutException.class);
        verify(postingService, times(1)).post(cmd);
        assertThat(retries()).isZero();
    }

    @Test
    void unexpectedErrorsAreNotRetried() {
        when(postingService.post(any())).thenThrow(new IllegalStateException("bug"));

        assertThatThrownBy(() -> facade.execute(cmd)).isInstanceOf(IllegalStateException.class);
        verify(postingService, times(1)).post(cmd);
    }

    @Test
    void keyReuseIsNotRetried() {
        when(postingService.post(any()))
                .thenThrow(new LedgerException(ErrorCode.IDEMPOTENCY_KEY_REUSED, "reused"));

        assertThatThrownBy(() -> facade.execute(cmd)).isInstanceOf(LedgerException.class);
        verify(postingService, times(1)).post(cmd);
    }

    @Test
    void secondReversalHittingTheUniqueConstraintIsA409AlreadyReversed() {
        UUID originalId = UUID.randomUUID();
        PostingCommand reversal = PostingCommand.of(TransactionType.REVERSAL, "key-87654321", null, null, 0,
                "INR", null, originalId);
        when(postingService.post(any())).thenThrow(uniqueViolation("transactions_reverses_txn_id_key"));

        assertThatThrownBy(() -> facade.execute(reversal))
                .isInstanceOfSatisfying(LedgerException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.ALREADY_REVERSED);
                    assertThat(e.getProperties()).containsEntry("transactionId", originalId);
                });
        verify(postingService, times(1)).post(reversal);
        verify(cache).clearInProgress(reversal, "marker");
    }

    @Test
    void otherConstraintViolationsAreNotDisguisedAsAlreadyReversed() {
        PostingCommand reversal = PostingCommand.of(TransactionType.REVERSAL, "key-87654321", null, null, 0,
                "INR", null, UUID.randomUUID());
        when(postingService.post(any())).thenThrow(uniqueViolation("transactions_idempotency_key_key"));

        assertThatThrownBy(() -> facade.execute(reversal)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void reversalConstraintNameOnANonReversalIsRethrownAsIs() {
        when(postingService.post(any())).thenThrow(uniqueViolation("transactions_reverses_txn_id_key"));

        assertThatThrownBy(() -> facade.execute(cmd)).isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------- metrics ----------

    @Test
    void newPostingIsCountedAsCreated() {
        when(postingService.post(cmd)).thenReturn(ok);
        facade.execute(cmd);
        assertThat(postings("TRANSFER", "created")).isEqualTo(1);
        assertThat(replays("redis") + replays("db")).isZero();
    }

    @Test
    void storedBusinessRejectionIsCountedAsRejected() {
        when(postingService.post(cmd)).thenReturn(new PostingResult(422, "{}", null, false));
        facade.execute(cmd);
        assertThat(postings("TRANSFER", "rejected")).isEqualTo(1);
    }

    @Test
    void redisReplayIsCountedWithSourceRedis() {
        when(cache.lookup(cmd)).thenReturn(Optional.of(new PostingResult(201, "{}", UUID.randomUUID(), true)));
        facade.execute(cmd);
        assertThat(replays("redis")).isEqualTo(1);
        assertThat(replays("db")).isZero();
        assertThat(postings("TRANSFER", "replayed")).isEqualTo(1);
    }

    @Test
    void replayAfterLosingTheSetNxRaceIsStillSourceRedis() {
        when(cache.tryMarkInProgress(cmd)).thenReturn(IdempotencyCache.Claim.TAKEN);
        when(cache.lookup(cmd))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new PostingResult(201, "{}", UUID.randomUUID(), true)));
        facade.execute(cmd);
        assertThat(replays("redis")).isEqualTo(1);
    }

    @Test
    void postgresReplayIsCountedWithSourceDb() {
        when(postingService.post(cmd)).thenReturn(new PostingResult(201, "{}", UUID.randomUUID(), true));
        facade.execute(cmd);
        assertThat(replays("db")).isEqualTo(1);
        assertThat(replays("redis")).isZero();
        assertThat(postings("TRANSFER", "replayed")).isEqualTo(1);
    }

    @Test
    void inProgressAndKeyReuseAreCountedAsConflicts() {
        when(cache.lookup(cmd)).thenThrow(new LedgerException(ErrorCode.REQUEST_IN_PROGRESS, "busy"));
        assertThatThrownBy(() -> facade.execute(cmd)).isInstanceOf(LedgerException.class);

        PostingCommand other = PostingCommand.of(TransactionType.DEPOSIT, "key-reused01", "SYS-CASH-0001",
                "ACC1000000001", 100, "INR", null, null);
        when(postingService.post(other)).thenThrow(new LedgerException(ErrorCode.IDEMPOTENCY_KEY_REUSED, "x"));
        assertThatThrownBy(() -> facade.execute(other)).isInstanceOf(LedgerException.class);

        assertThat(postings("TRANSFER", "conflict")).isEqualTo(1);
        assertThat(postings("DEPOSIT", "conflict")).isEqualTo(1);
    }

    @Test
    void systemBusyAndUnexpectedFailuresAreCountedAsErrors() {
        when(postingService.post(any())).thenThrow(new PessimisticLockingFailureException("lock timeout"));
        assertThatThrownBy(() -> facade.execute(cmd)).isInstanceOf(LedgerException.class);
        assertThat(postings("TRANSFER", "error")).isEqualTo(1);

        doThrow(new IllegalStateException("bug")).when(postingService).post(any());
        assertThatThrownBy(() -> facade.execute(cmd)).isInstanceOf(IllegalStateException.class);
        assertThat(postings("TRANSFER", "error")).isEqualTo(2);
    }

    // ---------- logging context ----------

    @Test
    void idempotencyKeyIsInTheLogContextWhilePostingAndClearedAfter() {
        String[] seen = new String[1];
        when(postingService.post(cmd)).thenAnswer(inv -> {
            seen[0] = MDC.get("idempotencyKey");
            return ok;
        });
        facade.execute(cmd);
        assertThat(seen[0]).isEqualTo("key-12345678");
        assertThat(MDC.get("idempotencyKey")).isNull();
    }

    @Test
    void idempotencyKeyIsClearedEvenWhenPostingFails() {
        when(postingService.post(any())).thenThrow(new IllegalStateException("bug"));
        assertThatThrownBy(() -> facade.execute(cmd)).isInstanceOf(IllegalStateException.class);
        assertThat(MDC.get("idempotencyKey")).isNull();
    }

    private double postings(String type, String result) {
        Counter c = registry.find("ledger.postings").tags("type", type, "result", result).counter();
        return c == null ? 0 : c.count();
    }

    private double replays(String source) {
        Counter c = registry.find("ledger.idempotency.replays").tag("source", source).counter();
        return c == null ? 0 : c.count();
    }

    private static DataIntegrityViolationException uniqueViolation(String constraint) {
        return new DataIntegrityViolationException("duplicate key", new ConstraintViolationException(
                "duplicate key", new SQLException("duplicate key value violates unique constraint", "23505"),
                constraint));
    }

    private double retries() {
        return registry.counter("ledger.lock.retries").count();
    }
}

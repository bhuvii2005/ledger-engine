package com.bhuvan.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bhuvan.ledger.entity.TransactionType;
import com.bhuvan.ledger.exception.ErrorCode;
import com.bhuvan.ledger.exception.LedgerException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;

class TransactionFacadeTest {

    private final PostingService postingService = mock(PostingService.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private TransactionFacade facade;

    private final PostingCommand cmd = PostingCommand.of(TransactionType.TRANSFER, "key-12345678",
            "ACC1000000001", "ACC1000000002", 100, "INR", null, null);
    private final PostingResult ok = new PostingResult(201, "{}", UUID.randomUUID(), false);

    @BeforeEach
    void setUp() {
        facade = new TransactionFacade(postingService, new IdempotencyCache(), registry, 3, 0);
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

    private double retries() {
        return registry.counter("ledger.lock.retries").count();
    }
}

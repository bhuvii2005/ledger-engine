package com.bhuvan.ledger.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.bhuvan.ledger.AbstractIntegrationTest;
import com.bhuvan.ledger.entity.TransactionType;
import com.bhuvan.ledger.repository.AccountRepository;
import com.bhuvan.ledger.service.AccountService;
import com.bhuvan.ledger.service.PostingCommand;
import com.bhuvan.ledger.service.PostingService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The retry loop catches PessimisticLockingFailureException. These tests make Postgres
 * raise a real deadlock (40P01) and a real lock timeout (55P03) through the same JPA
 * stack the app uses, and check what actually comes out on our Spring/Hibernate versions.
 */
class LockExceptionTranslationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(LockExceptionTranslationTest.class);

    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PostingService postingService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DataSource dataSource;

    @Test
    @Timeout(30)
    void deadlockSurfacesAsPessimisticLockingFailure() throws Exception {
        Long a = idOf(accountService.create("Deadlock A").accountNumber());
        Long b = idOf(accountService.create("Deadlock B").accountNumber());
        CyclicBarrier bothHoldOneLock = new CyclicBarrier(2);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // opposite order on purpose: exactly what PostingService's id ordering prevents
            Future<?> first = pool.submit(() -> lockBoth(a, b, bothHoldOneLock));
            Future<?> second = pool.submit(() -> lockBoth(b, a, bothHoldOneLock));

            List<Throwable> failures = new ArrayList<>();
            for (Future<?> f : List.of(first, second)) {
                try {
                    f.get(20, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                }
            }

            // Postgres picks one victim; the other transaction then gets both locks
            assertThat(failures).hasSize(1);
            Throwable victim = failures.get(0);
            log.info("Deadlock surfaced as {} (SQLState {})", victim.getClass().getName(), sqlState(victim));
            assertThat(victim).isInstanceOf(PessimisticLockingFailureException.class);
            assertThat(sqlState(victim)).isEqualTo("40P01");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Timeout(30)
    void lockTimeoutOnAnAccountSurfacesAsPessimisticLockingFailure() throws Exception {
        String number = accountService.create("Timeout").accountNumber();
        Long id = idOf(number);
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            run(holder, "select id from accounts where id = ? for update", id);

            Throwable thrown = catchThrowable(() ->
                    transactionTemplate.executeWithoutResult(s -> accountRepository.lockById(id)));

            log.info("Lock timeout surfaced as {} (SQLState {})", thrown.getClass().getName(), sqlState(thrown));
            assertThat(thrown).isInstanceOf(PessimisticLockingFailureException.class);
            assertThat(sqlState(thrown)).isEqualTo("55P03");
            holder.rollback();
        }
    }

    @Test
    @Timeout(30)
    void waitingOnAnUncommittedIdempotencyKeyTimesOutAsPessimisticLockingFailure() throws Exception {
        // the claim INSERT blocks on the unique index while another transaction holds the same key
        String key = "held-key-" + System.nanoTime();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            run(holder, "insert into idempotency_keys (idem_key, request_hash, operation, status) "
                    + "values (?, ?, 'DEPOSIT', 'IN_PROGRESS')", key, "0".repeat(64));

            PostingCommand cmd = PostingCommand.of(TransactionType.DEPOSIT, key, "SYS-CASH-0001",
                    "ACC0000000000", 100, "INR", null, null);
            Throwable thrown = catchThrowable(() -> postingService.post(cmd));

            log.info("Idempotency claim timeout surfaced as {} (SQLState {})",
                    thrown.getClass().getName(), sqlState(thrown));
            assertThat(thrown).isInstanceOf(PessimisticLockingFailureException.class);
            assertThat(sqlState(thrown)).isEqualTo("55P03");
            holder.rollback();
        }
    }

    private void lockBoth(Long firstId, Long secondId, CyclicBarrier barrier) {
        transactionTemplate.executeWithoutResult(status -> {
            accountRepository.lockById(firstId);
            try {
                barrier.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            accountRepository.lockById(secondId);
        });
    }

    private Long idOf(String accountNumber) {
        return accountRepository.findIdByAccountNumber(accountNumber).orElseThrow();
    }

    private static String sqlState(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    private static void run(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.execute();
        }
    }
}

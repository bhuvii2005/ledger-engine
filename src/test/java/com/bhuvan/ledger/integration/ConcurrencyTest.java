package com.bhuvan.ledger.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.bhuvan.ledger.AbstractIntegrationTest;
import com.bhuvan.ledger.LedgerInvariants;
import com.bhuvan.ledger.entity.TransactionType;
import com.bhuvan.ledger.exception.LedgerException;
import com.bhuvan.ledger.service.AccountService;
import com.bhuvan.ledger.service.PostingCommand;
import com.bhuvan.ledger.service.PostingResult;
import com.bhuvan.ledger.service.TransactionFacade;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Concurrency tests 1-4 from the plan, DB-only (the Redis cache is still a no-op).
 * Each one releases all threads at once with a latch, then checks money was conserved
 * and the ledger is consistent.
 */
class ConcurrencyTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyTest.class);
    private static final String SYSTEM_ACCOUNT = "SYS-CASH-0001";
    private static final int DEADLOCK_BAIT_THREADS = 40;

    @Autowired
    private TransactionFacade facade;

    @Autowired
    private AccountService accountService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private MockMvc mockMvc;

    private LedgerInvariants ledger;

    @BeforeEach
    void setUp() {
        ledger = new LedgerInvariants(jdbc);
    }

    @Test
    @Timeout(60)
    void doubleSpend() throws Exception {
        String acc = accountWith(10_000); // 100.00

        List<Callable<PostingResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            tasks.add(() -> facade.execute(withdrawal(acc, 1_000))); // 10.00 each, unique keys
        }
        Map<String, Integer> outcomes = tally(runAtOnce(tasks, 50));

        log.info("RESULT doubleSpend: {} -> final balance {}", outcomes, ledger.balance(acc));
        assertThat(outcomes).containsEntry("201", 10).containsEntry("422 INSUFFICIENT_FUNDS", 40).hasSize(2);
        assertThat(ledger.balance(acc)).isZero();
        assertThat(transactionsOn(acc)).isEqualTo(11); // the opening deposit + 10 withdrawals
        ledger.assertHealthy(List.of(acc));
    }

    @Test
    @Timeout(60)
    void deadlockBait() throws Exception {
        String a = accountWith(1_000_000); // 10,000.00
        String b = accountWith(1_000_000);
        double retriesBefore = lockRetries();

        // 200 opposite-direction transfers on 40 threads (5 each). Every transfer needs the same two
        // row locks, so they run one at a time; 40 in flight keeps the queue for the 20-connection pool
        // well inside Hikari's 3s connection-timeout. 200 threads at once would turn the tail of the
        // queue into pool-wait 503s, which is a capacity limit, not what this test is about.
        List<Callable<PostingResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            boolean aToB = i % 2 == 0;
            tasks.add(() -> facade.execute(transfer(aToB ? a : b, aToB ? b : a, 100))); // 1.00
        }
        long started = System.nanoTime();
        Map<String, Integer> outcomes = tally(runAtOnce(tasks, DEADLOCK_BAIT_THREADS));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        double retries = lockRetries() - retriesBefore;

        log.info("RESULT deadlockBait: {} on {} threads in {} ms ; A={} B={} ; ledger.lock.retries delta={}",
                outcomes, DEADLOCK_BAIT_THREADS, elapsedMs, ledger.balance(a), ledger.balance(b), retries);
        assertThat(outcomes).containsEntry("201", 200).hasSize(1);
        // 100 each way, so both end exactly where they started
        assertThat(ledger.balance(a)).isEqualTo(1_000_000);
        assertThat(ledger.balance(b)).isEqualTo(1_000_000);
        // a deadlock victim would have been retried; zero retries means zero deadlocks (and no lock timeouts)
        assertThat(retries).isZero();
        ledger.assertHealthy(List.of(a, b));
    }

    @Test
    @Timeout(60)
    void randomMesh() throws Exception {
        List<String> accounts = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            accounts.add(accountWith(100_000)); // 1,000.00 each
        }
        long totalBefore = ledger.totalOf(accounts);

        Random random = new Random(42); // fixed seed so a failure can be replayed
        List<Callable<PostingResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 2_000; i++) {
            int from = random.nextInt(20);
            int to = (from + 1 + random.nextInt(19)) % 20; // never the same account
            long amount = 100L * (1 + random.nextInt(200)); // 1.00 .. 200.00
            String src = accounts.get(from);
            String dst = accounts.get(to);
            tasks.add(() -> facade.execute(transfer(src, dst, amount)));
        }
        Map<String, Integer> outcomes = tally(runAtOnce(tasks, 32));

        long totalAfter = ledger.totalOf(accounts);
        log.info("RESULT randomMesh: {} ; total before={} after={}", outcomes, totalBefore, totalAfter);
        assertThat(outcomes.keySet()).allMatch(k -> k.equals("201") || k.equals("422 INSUFFICIENT_FUNDS"));
        assertThat(outcomes.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(2_000);
        assertThat(totalAfter).isEqualTo(totalBefore);
        assertThat(accounts).allMatch(acc -> ledger.balance(acc) >= 0);
        ledger.assertHealthy(accounts);
    }

    @Test
    @Timeout(60)
    void sameKeyStorm() throws Exception {
        String a = accountWith(100_000);
        String b = accountWith(0);
        PostingCommand same = transfer(a, b, 1_000); // one key, one body, 50 senders

        List<Callable<PostingResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            tasks.add(() -> facade.execute(same));
        }
        List<Object> results = runAtOnce(tasks, 50);
        Map<String, Integer> outcomes = tally(results);

        long originals = results.stream().filter(r -> r instanceof PostingResult p && !p.replayed()).count();
        long replays = results.stream().filter(r -> r instanceof PostingResult p && p.replayed()).count();
        long distinctIds = results.stream().filter(r -> r instanceof PostingResult)
                .map(r -> ((PostingResult) r).transactionId()).distinct().count();
        long distinctBodies = results.stream().filter(r -> r instanceof PostingResult)
                .map(r -> ((PostingResult) r).body()).distinct().count();

        log.info("RESULT sameKeyStorm: {} ; originals={} replays={} distinctTransactionIds={} "
                + "transactionRows={}", outcomes, originals, replays, distinctIds, transactionsWithKey(same));
        // Without Redis, the other 49 block on the key's unique index until the first commits,
        // then read the stored row and replay it. (The 409 REQUEST_IN_PROGRESS path needs Redis.)
        assertThat(outcomes).containsEntry("201", 50).hasSize(1);
        assertThat(originals).isEqualTo(1);
        assertThat(replays).isEqualTo(49);
        assertThat(distinctIds).isEqualTo(1);
        assertThat(distinctBodies).isEqualTo(1);
        assertThat(transactionsWithKey(same)).isEqualTo(1);
        assertThat(ledger.balance(a)).isEqualTo(99_000);
        assertThat(ledger.balance(b)).isEqualTo(1_000);
        ledger.assertHealthy(List.of(a, b));
    }

    @Test
    @Timeout(60)
    void sameKeyStormThroughHttp() throws Exception {
        String a = accountWith(100_000);
        String b = accountWith(0);
        String key = "http-storm-" + UUID.randomUUID();
        String body = "{\"fromAccount\":\"" + a + "\",\"toAccount\":\"" + b + "\",\"amount\":\"10.00\"}";

        List<Callable<MockHttpServletResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            tasks.add(() -> mockMvc.perform(post("/api/v1/transactions/transfer")
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body)).andReturn().getResponse());
        }
        List<Object> results = runAtOnce(tasks, 50);

        Map<String, Integer> statuses = new TreeMap<>();
        List<String> ids = new ArrayList<>();
        int replayed = 0;
        for (Object r : results) {
            if (r instanceof MockHttpServletResponse resp) {
                statuses.merge(String.valueOf(resp.getStatus()), 1, Integer::sum);
                ids.add(JsonPath.read(resp.getContentAsString(), "$.transactionId"));
                if ("true".equals(resp.getHeader("Idempotent-Replayed"))) {
                    replayed++;
                }
            } else {
                statuses.merge("exception " + ((Throwable) r).getClass().getSimpleName(), 1, Integer::sum);
            }
        }

        log.info("RESULT sameKeyStormThroughHttp: {} ; replayed header on {} ; distinct ids {}",
                statuses, replayed, ids.stream().distinct().count());
        assertThat(statuses).containsEntry("201", 50).hasSize(1);
        assertThat(replayed).isEqualTo(49);
        assertThat(ids.stream().distinct().count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from transactions where idempotency_key = ?",
                Integer.class, key)).isEqualTo(1);
        ledger.assertHealthy(List.of(a, b));
    }

    // ---------- helpers ----------

    private String accountWith(long paise) {
        String acc = accountService.create("Concurrency").accountNumber();
        if (paise > 0) {
            PostingResult r = facade.execute(PostingCommand.of(TransactionType.DEPOSIT, newKey(),
                    SYSTEM_ACCOUNT, acc, paise, "INR", null, null));
            assertThat(r.httpStatus()).isEqualTo(201);
        }
        return acc;
    }

    private static PostingCommand withdrawal(String acc, long paise) {
        return PostingCommand.of(TransactionType.WITHDRAWAL, newKey(), acc, SYSTEM_ACCOUNT, paise, "INR", null, null);
    }

    private static PostingCommand transfer(String from, String to, long paise) {
        return PostingCommand.of(TransactionType.TRANSFER, newKey(), from, to, paise, "INR", null, null);
    }

    private static String newKey() {
        return "c-" + UUID.randomUUID();
    }

    // Starts every task on its own thread pool slot, holds them at a latch, then releases all at once.
    // Each element of the result is either the task's return value or the exception it threw.
    private static <T> List<Object> runAtOnce(List<Callable<T>> tasks, int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(Math.min(threads, tasks.size()));
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();

            List<Object> results = new ArrayList<>();
            for (Future<T> f : futures) {
                try {
                    results.add(f.get(50, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    results.add(e.getCause());
                }
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    // "201", "422 INSUFFICIENT_FUNDS", "exception LedgerException SYSTEM_BUSY", ...
    private static Map<String, Integer> tally(List<Object> results) {
        Map<String, Integer> counts = new TreeMap<>();
        for (Object r : results) {
            String label;
            if (r instanceof PostingResult p) {
                label = p.isSuccess() ? String.valueOf(p.httpStatus())
                        : p.httpStatus() + " " + JsonPath.read(p.body(), "$.code");
            } else if (r instanceof LedgerException e) {
                label = "exception " + e.getCode();
            } else {
                label = "exception " + ((Throwable) r).getClass().getSimpleName();
            }
            counts.merge(label, 1, Integer::sum);
        }
        return counts;
    }

    private double lockRetries() {
        return meterRegistry.counter("ledger.lock.retries").count();
    }

    private int transactionsOn(String acc) {
        return jdbc.queryForObject("""
                select count(*) from ledger_entries e join accounts a on a.id = e.account_id
                 where a.account_number = ?
                """, Integer.class, acc);
    }

    private int transactionsWithKey(PostingCommand cmd) {
        return jdbc.queryForObject("select count(*) from transactions where idempotency_key = ?",
                Integer.class, cmd.idempotencyKey());
    }
}

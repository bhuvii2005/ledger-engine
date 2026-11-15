package com.bhuvan.ledger.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bhuvan.ledger.AbstractIntegrationTest;
import com.bhuvan.ledger.LedgerInvariants;
import com.bhuvan.ledger.entity.TransactionType;
import com.bhuvan.ledger.service.AccountService;
import com.bhuvan.ledger.service.IdempotencyCache;
import com.bhuvan.ledger.service.PostingCommand;
import com.bhuvan.ledger.service.PostingResult;
import com.bhuvan.ledger.service.PostingService;
import com.bhuvan.ledger.service.ReconciliationService;
import com.bhuvan.ledger.service.TransactionFacade;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The load-test 409: Redis applies the IN_PROGRESS SET NX but the app gets a timeout instead
 * of the reply. The request must still post exactly once, and must never answer 409 because
 * of the marker it wrote itself.
 *
 * <p>Uses the real Redis, the real PostingService and Postgres. Only the cache's Redis client
 * is wrapped, so the SET really lands in Redis before the error is thrown.
 */
class LostSetNxReplyTest extends AbstractIntegrationTest {

    private static final String SYSTEM_ACCOUNT = "SYS-CASH-0001";

    @Autowired
    private RedisConnectionFactory connectionFactory;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private PostingService postingService;

    @Autowired
    private TransactionFacade appFacade;

    @Autowired
    private AccountService accountService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReconciliationService reconciliation;

    @Value("${ledger.idempotency.in-progress-ttl}")
    private Duration inProgressTtl;

    @Value("${ledger.idempotency.completed-ttl}")
    private Duration completedTtl;

    @Value("${ledger.idempotency.redis-skip-after-failure}")
    private Duration skipWindow;

    @Value("${ledger.idempotency.redis-warn-interval}")
    private Duration warnInterval;

    @Value("${ledger.posting.max-attempts}")
    private int maxAttempts;

    @Value("${ledger.posting.retry-backoff-ms}")
    private long backoffMs;

    private String a;
    private String b;

    @BeforeEach
    void setUp() {
        a = accountService.create("Lost Reply A").accountNumber();
        b = accountService.create("Lost Reply B").accountNumber();
        PostingResult funded = appFacade.execute(PostingCommand.of(TransactionType.DEPOSIT, newKey(),
                SYSTEM_ACCOUNT, a, 100_000, "INR", null, null));
        assertThat(funded.httpStatus()).isEqualTo(201);
    }

    @Test
    void setNxAppliedButTimedOutStillPostsExactlyOnceAndNever409s() throws Exception {
        // The error surfaces after the skip window has run out, as it did in the load test
        // (that request took 1.65s). Before the fix, the facade then looked Redis up again,
        // found the marker this same request had written, and answered 409.
        LostReplyRedis lossy = new LostReplyRedis(connectionFactory, skipWindow.plusMillis(200), false);
        TransactionFacade facade = facadeOver(lossy);
        PostingCommand cmd = transfer(newKey());
        double failuresBefore = redisFailures();

        PostingResult result = facade.execute(cmd);

        assertThat(lossy.setNxCalls.get()).isEqualTo(1);
        assertThat(result.httpStatus()).isEqualTo(201);
        assertThat(result.replayed()).isFalse();
        assertThat(transactionsWithKey(cmd.idempotencyKey())).isEqualTo(1);
        assertThat(redisFailures() - failuresBefore).isEqualTo(1); // the lost reply, nothing else
        // the read-back showed Redis was fine, so the completed result replaced the marker
        assertThat(redis.opsForValue().get(redisKey(cmd))).contains("\"state\":\"COMPLETED\"");

        // and the same key now replays from Redis through the normal app
        PostingResult replay = appFacade.execute(cmd);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.transactionId()).isEqualTo(result.transactionId());
        assertThat(transactionsWithKey(cmd.idempotencyKey())).isEqualTo(1);
        new LedgerInvariants(jdbc, reconciliation).assertHealthy();
    }

    @Test
    void setNxThatTimesOutAtOnceIsAlsoClaimed() {
        LostReplyRedis lossy = new LostReplyRedis(connectionFactory, Duration.ZERO, false);
        PostingCommand cmd = transfer(newKey());

        PostingResult result = facadeOver(lossy).execute(cmd);

        assertThat(result.httpStatus()).isEqualTo(201);
        assertThat(transactionsWithKey(cmd.idempotencyKey())).isEqualTo(1);
        assertThat(redis.opsForValue().get(redisKey(cmd))).contains("\"state\":\"COMPLETED\"");
    }

    @Test
    void failedPostingAfterALostReplyStillDeletesItsOwnMarker() {
        LostReplyRedis lossy = new LostReplyRedis(connectionFactory, Duration.ZERO, false);
        PostingService failing = mock(PostingService.class);
        when(failing.post(any())).thenThrow(new IllegalStateException("posting failed"));
        TransactionFacade facade = new TransactionFacade(failing, cacheOver(lossy), meterRegistry,
                maxAttempts, backoffMs);
        PostingCommand cmd = transfer(newKey());

        assertThatThrownBy(() -> facade.execute(cmd)).isInstanceOf(IllegalStateException.class);

        // compare-and-delete found the marker it owned and removed it, so a retry gets straight in
        assertThat(lossy.setNxCalls.get()).isEqualTo(1);
        assertThat(redis.hasKey(redisKey(cmd))).isFalse();
        PostingResult retry = appFacade.execute(cmd);
        assertThat(retry.httpStatus()).isEqualTo(201);
        assertThat(transactionsWithKey(cmd.idempotencyKey())).isEqualTo(1);
    }

    @Test
    void lostReplyWithRedisUnreadableFallsThroughToPostgres() {
        LostReplyRedis lossy = new LostReplyRedis(connectionFactory, Duration.ZERO, true);
        PostingCommand cmd = transfer(newKey());

        PostingResult result = facadeOver(lossy).execute(cmd);

        // the read-back failed too, so the cache can't vouch either way: a miss, Postgres decides
        assertThat(result.httpStatus()).isEqualTo(201);
        assertThat(result.replayed()).isFalse();
        assertThat(transactionsWithKey(cmd.idempotencyKey())).isEqualTo(1);
        new LedgerInvariants(jdbc, reconciliation).assertHealthy();
    }

    // ---------- helpers ----------

    private TransactionFacade facadeOver(StringRedisTemplate template) {
        return new TransactionFacade(postingService, cacheOver(template), meterRegistry, maxAttempts, backoffMs);
    }

    private IdempotencyCache cacheOver(StringRedisTemplate template) {
        return new IdempotencyCache(template, objectMapper, meterRegistry, inProgressTtl, completedTtl, skipWindow,
                warnInterval);
    }

    private PostingCommand transfer(String key) {
        return PostingCommand.of(TransactionType.TRANSFER, key, a, b, 1_000, "INR", null, null);
    }

    private int transactionsWithKey(String key) {
        return jdbc.queryForObject("select count(*) from transactions where idempotency_key = ?", Integer.class, key);
    }

    private double redisFailures() {
        return meterRegistry.counter("ledger.redis.failures").count();
    }

    private static String redisKey(PostingCommand cmd) {
        return "idem:" + cmd.idempotencyKey();
    }

    private static String newKey() {
        return "lost-" + UUID.randomUUID();
    }

    /**
     * Talks to the real Redis, except that SET NX (setIfAbsent) is carried out and then
     * reported as a command timeout, after an optional delay. With readsFailAfterSet, every
     * GET after that SET fails too, as if Redis had become unreachable.
     */
    static final class LostReplyRedis extends StringRedisTemplate {

        final AtomicInteger setNxCalls = new AtomicInteger();
        private final Duration errorDelay;
        private final boolean readsFailAfterSet;
        private final AtomicBoolean setDone = new AtomicBoolean();

        LostReplyRedis(RedisConnectionFactory factory, Duration errorDelay, boolean readsFailAfterSet) {
            super(factory);
            this.errorDelay = errorDelay;
            this.readsFailAfterSet = readsFailAfterSet;
        }

        @Override
        @SuppressWarnings("unchecked")
        public ValueOperations<String, String> opsForValue() {
            ValueOperations<String, String> real = super.opsForValue();
            return (ValueOperations<String, String>) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {ValueOperations.class}, (proxy, method, args) -> {
                        if (method.getName().equals("setIfAbsent")) {
                            setNxCalls.incrementAndGet();
                            invoke(method, real, args); // Redis applies it...
                            setDone.set(true);
                            Thread.sleep(errorDelay.toMillis());
                            throw new QueryTimeoutException("Redis command timed out"); // ...the reply is lost
                        }
                        if (readsFailAfterSet && setDone.get() && method.getName().equals("get")) {
                            throw new QueryTimeoutException("Redis command timed out");
                        }
                        return invoke(method, real, args);
                    });
        }

        private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }
}

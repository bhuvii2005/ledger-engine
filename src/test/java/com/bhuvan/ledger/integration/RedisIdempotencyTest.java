package com.bhuvan.ledger.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.bhuvan.ledger.AbstractIntegrationTest;
import com.bhuvan.ledger.LedgerInvariants;
import com.bhuvan.ledger.entity.TransactionType;
import com.bhuvan.ledger.service.AccountService;
import com.bhuvan.ledger.service.IdempotencyCache;
import com.bhuvan.ledger.service.PostingCommand;
import com.bhuvan.ledger.service.PostingResult;
import com.bhuvan.ledger.service.PostingService;
import com.bhuvan.ledger.service.TransactionFacade;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/** The Redis fast path in front of the Postgres idempotency table, end to end over HTTP. */
class RedisIdempotencyTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(RedisIdempotencyTest.class);
    private static final String SYSTEM_ACCOUNT = "SYS-CASH-0001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private IdempotencyCache cache;

    @Autowired
    private PostingService postingService;

    @Autowired
    private TransactionFacade facade;

    @Autowired
    private AccountService accountService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private ObjectMapper objectMapper;

    private LedgerInvariants ledger;
    private String a;
    private String b;

    @BeforeEach
    void setUp() {
        ledger = new LedgerInvariants(jdbc);
        a = accountWith(100_000); // 1,000.00
        b = accountWith(0);
    }

    @Test
    void completedPostingIsCachedInRedisFor24Hours() throws Exception {
        String key = newKey();
        MockHttpServletResponse first = transfer(key, a, b, "10.00");
        assertThat(first.getStatus()).isEqualTo(201);

        JsonNode entry = redisEntry(key);
        assertThat(entry.get("state").asText()).isEqualTo("COMPLETED");
        assertThat(entry.get("httpStatus").asInt()).isEqualTo(201);
        assertThat(entry.get("transactionId").asText()).isEqualTo(JsonPath.read(first.getContentAsString(),
                "$.transactionId"));
        assertThat(entry.get("body").asText()).isEqualTo(first.getContentAsString());
        assertThat(entry.has("owner")).isFalse();
        assertThat(redis.getExpire(redisKey(key), TimeUnit.SECONDS)).isBetween(86_390L, 86_400L);
    }

    @Test
    void replayIsServedFromRedisWithZeroDatabaseStatements() throws Exception {
        String key = newKey();
        MockHttpServletResponse first = transfer(key, a, b, "10.00");
        assertThat(first.getStatus()).isEqualTo(201);

        Statistics stats = statistics();
        stats.clear();
        MockHttpServletResponse replay = transfer(key, a, b, "10.00");
        long redisReplayStatements = stats.getPrepareStatementCount();
        long redisReplaySessions = stats.getSessionOpenCount();
        long redisReplayTransactions = stats.getTransactionCount();

        assertThat(replay.getStatus()).isEqualTo(201);
        assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(replay.getHeader("Location")).isEqualTo(first.getHeader("Location"));
        assertThat(redisReplayStatements).isZero();
        assertThat(redisReplaySessions).isZero();
        assertThat(redisReplayTransactions).isZero();

        // Control: the same replay with the Redis entry gone has to read Postgres, which shows
        // the counter above really would have seen database work. It also refills Redis.
        redis.delete(redisKey(key));
        stats.clear();
        MockHttpServletResponse dbReplay = transfer(key, a, b, "10.00");
        long dbReplayStatements = stats.getPrepareStatementCount();

        log.info("RESULT replay: Redis replay ran {} SQL statements / {} sessions / {} transactions; "
                + "DB replay ran {} SQL statements", redisReplayStatements, redisReplaySessions,
                redisReplayTransactions, dbReplayStatements);
        assertThat(dbReplay.getStatus()).isEqualTo(201);
        assertThat(dbReplay.getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(dbReplay.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(dbReplayStatements).isPositive();
        assertThat(redisEntry(key).get("state").asText()).isEqualTo("COMPLETED");

        assertThat(transactionsWithKey(key)).isEqualTo(1);
        assertThat(ledger.balance(b)).isEqualTo(1_000);
    }

    @Test
    void inProgressMarkerGives409WithRetryAfterAndPostsNothing() throws Exception {
        String key = newKey();
        assertThat(cache.tryMarkInProgress(transferCommand(key, a, b, 1_000))).isPresent();

        MockHttpServletResponse response = transfer(key, a, b, "10.00");

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        assertThat((String) JsonPath.read(response.getContentAsString(), "$.code")).isEqualTo("REQUEST_IN_PROGRESS");
        assertThat(transactionsWithKey(key)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from idempotency_keys where idem_key = ?",
                Integer.class, key)).isZero();
    }

    @Test
    void sameKeyWithADifferentBodyIsRejectedFromRedisWhileInProgress() throws Exception {
        String key = newKey();
        assertThat(cache.tryMarkInProgress(transferCommand(key, a, b, 1_000))).isPresent();

        MockHttpServletResponse response = transfer(key, a, b, "20.00");

        assertThat(response.getStatus()).isEqualTo(422);
        assertThat((String) JsonPath.read(response.getContentAsString(), "$.code"))
                .isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(redisEntry(key).get("state").asText()).isEqualTo("IN_PROGRESS"); // left alone
    }

    @Test
    void sameKeyWithADifferentBodyOrEndpointIsRejectedFromRedisOnceCompleted() throws Exception {
        String key = newKey();
        assertThat(transfer(key, a, b, "10.00").getStatus()).isEqualTo(201);

        Statistics stats = statistics();
        stats.clear();
        MockHttpServletResponse differentAmount = transfer(key, a, b, "20.00");
        MockHttpServletResponse differentEndpoint = mockMvc.perform(post("/api/v1/transactions/deposit")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountNumber\":\"" + a + "\",\"amount\":\"10.00\"}")).andReturn().getResponse();

        for (MockHttpServletResponse r : List.of(differentAmount, differentEndpoint)) {
            assertThat(r.getStatus()).isEqualTo(422);
            assertThat((String) JsonPath.read(r.getContentAsString(), "$.code")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        }
        assertThat(stats.getPrepareStatementCount()).isZero(); // answered by Redis alone
        assertThat(transactionsWithKey(key)).isEqualTo(1);
    }

    @Test
    void businessRejectionIsCachedAndReplayedIdentically() throws Exception {
        String key = newKey();
        MockHttpServletResponse first = transfer(key, b, a, "5.00"); // b is empty
        assertThat(first.getStatus()).isEqualTo(422);
        assertThat(redisEntry(key).get("httpStatus").asInt()).isEqualTo(422);

        MockHttpServletResponse replay = transfer(key, b, a, "5.00");
        assertThat(replay.getStatus()).isEqualTo(422);
        assertThat(replay.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat((String) JsonPath.read(replay.getContentAsString(), "$.code")).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void inProgressMarkerIsSetNxWithATenSecondTtl() {
        PostingCommand cmd = transferCommand(newKey(), a, b, 1_000);

        Optional<String> marker = cache.tryMarkInProgress(cmd);

        assertThat(marker).isPresent();
        assertThat(redis.getExpire(redisKey(cmd.idempotencyKey()), TimeUnit.MILLISECONDS)).isBetween(9_000L, 10_000L);
        assertThat(cache.tryMarkInProgress(cmd)).isEmpty(); // NX: the second one loses
    }

    @Test
    void clearingRemovesOnlyTheMarkerThisRequestWrote() throws Exception {
        PostingCommand cmd = transferCommand(newKey(), a, b, 1_000);
        String mine = cache.tryMarkInProgress(cmd).orElseThrow();

        // my marker expired and another request with the same key marked it again
        redis.delete(redisKey(cmd.idempotencyKey()));
        String theirs = cache.tryMarkInProgress(cmd).orElseThrow();
        assertThat(theirs).isNotEqualTo(mine);

        cache.clearInProgress(cmd, mine);
        assertThat(redis.opsForValue().get(redisKey(cmd.idempotencyKey()))).isEqualTo(theirs);

        cache.clearInProgress(cmd, theirs);
        assertThat(redis.hasKey(redisKey(cmd.idempotencyKey()))).isFalse();
    }

    @Test
    void failedPostingClearsItsMarkerAndCachesNothing() throws Exception {
        String key = newKey();
        assertThat(transfer(key, a, b, "10.00").getStatus()).isEqualTo(201);
        // Redis forgot the key (eviction, restart): a different body now reaches Postgres,
        // which rejects it after this request has already written its IN_PROGRESS marker
        redis.delete(redisKey(key));

        MockHttpServletResponse reused = transfer(key, a, b, "20.00");

        assertThat(reused.getStatus()).isEqualTo(422);
        assertThat((String) JsonPath.read(reused.getContentAsString(), "$.code")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(redis.hasKey(redisKey(key))).isFalse();
        // and the original request still replays (from Postgres, refilling Redis)
        assertThat(transfer(key, a, b, "10.00").getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(redisEntry(key).get("state").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void unreadableRedisValueIsTreatedAsAMiss() throws Exception {
        String key = newKey();
        redis.opsForValue().set(redisKey(key), "not json");
        double failuresBefore = redisFailures();

        MockHttpServletResponse response = transfer(key, a, b, "10.00");

        // the miss lets the request through to Postgres, but the bad value still blocks SET NX,
        // so this request runs unmarked; the completed result then overwrites the bad value
        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(redisFailures() - failuresBefore).isGreaterThanOrEqualTo(1);
        assertThat(redisEntry(key).get("state").asText()).isEqualTo("COMPLETED");
        assertThat(transactionsWithKey(key)).isEqualTo(1);
    }

    @Test
    @Timeout(30)
    void crashBetweenCommitAndRedisWriteGives409UntilTheMarkerExpiresThenADatabaseReplay() throws Exception {
        String key = newKey();
        PostingCommand cmd = transferCommand(key, a, b, 1_000);

        // What the facade does, minus the last step: mark IN_PROGRESS, post and commit, then
        // "crash" before storeCompleted. Redis is left holding the marker with its real 10s TTL.
        long markedAt = System.nanoTime();
        assertThat(cache.tryMarkInProgress(cmd)).isPresent();
        PostingResult committed = postingService.post(cmd);
        assertThat(committed.httpStatus()).isEqualTo(201);

        // A client retrying the way Retry-After asks: once a second until it gets an answer.
        List<Integer> statuses = new ArrayList<>();
        MockHttpServletResponse response;
        while (true) {
            response = transfer(key, a, b, "10.00");
            statuses.add(response.getStatus());
            if (response.getStatus() != 409) {
                break;
            }
            assertThat(response.getHeader("Retry-After")).isEqualTo("1");
            Thread.sleep(1_000);
        }
        long answeredAfterMs = (System.nanoTime() - markedAt) / 1_000_000;

        log.info("RESULT crashWindow: statuses {} ; first non-409 answer {} ms after the marker was written",
                statuses, answeredAfterMs);
        assertThat(statuses.subList(0, statuses.size() - 1)).isNotEmpty().allMatch(s -> s == 409);
        assertThat(answeredAfterMs).isBetween(9_000L, 12_000L);
        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(response.getContentAsString()).isEqualTo(committed.body());
        assertThat(redisEntry(key).get("state").asText()).isEqualTo("COMPLETED"); // refilled from Postgres
        assertThat(transactionsWithKey(key)).isEqualTo(1);
        assertThat(ledger.balance(b)).isEqualTo(1_000);
        ledger.assertHealthy(List.of(a, b));
    }

    // ---------- helpers ----------

    private MockHttpServletResponse transfer(String key, String from, String to, String amount) throws Exception {
        return mockMvc.perform(post("/api/v1/transactions/transfer")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromAccount\":\"" + from + "\",\"toAccount\":\"" + to + "\",\"amount\":\""
                        + amount + "\"}")).andReturn().getResponse();
    }

    // the command the controller builds for the transfer() request above
    private static PostingCommand transferCommand(String key, String from, String to, long paise) {
        return PostingCommand.of(TransactionType.TRANSFER, key, from, to, paise, "INR", null, null);
    }

    private String accountWith(long paise) {
        String acc = accountService.create("Redis Cache").accountNumber();
        if (paise > 0) {
            PostingResult r = facade.execute(PostingCommand.of(TransactionType.DEPOSIT, newKey(),
                    SYSTEM_ACCOUNT, acc, paise, "INR", null, null));
            assertThat(r.httpStatus()).isEqualTo(201);
        }
        return acc;
    }

    private JsonNode redisEntry(String key) throws Exception {
        String json = redis.opsForValue().get(redisKey(key));
        assertThat(json).as("Redis entry for %s", key).isNotNull();
        return objectMapper.readTree(json);
    }

    private static String redisKey(String key) {
        return "idem:" + key;
    }

    private static String newKey() {
        return "r-" + UUID.randomUUID();
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private double redisFailures() {
        return meterRegistry.counter("ledger.redis.failures").count();
    }

    private int transactionsWithKey(String key) {
        return jdbc.queryForObject("select count(*) from transactions where idempotency_key = ?", Integer.class, key);
    }
}

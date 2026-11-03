package com.bhuvan.ledger.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.bhuvan.ledger.AbstractIntegrationTest;
import com.bhuvan.ledger.LedgerInvariants;
import com.bhuvan.ledger.service.AccountService;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/** Stops the real Redis container and checks the app carries on through Postgres alone. */
class RedisOutageTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(RedisOutageTest.class);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private AccountService accountService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Value("${ledger.idempotency.redis-skip-after-failure}")
    private Duration cacheSkipWindow;

    @Test
    @Timeout(120)
    void healthStaysUpAndPostingsKeepWorkingWhileRedisIsStopped() throws Exception {
        String acc = accountService.create("Outage").accountNumber();
        double failuresBefore = meterRegistry.counter("ledger.redis.failures").count();

        stopRedis();
        MockHttpServletResponse health;
        List<Long> latenciesMs = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        MockHttpServletResponse replay;
        try {
            health = mockMvc.perform(get("/actuator/health")).andReturn().getResponse();

            for (int i = 0; i < 20; i++) {
                String key = "outage-" + UUID.randomUUID();
                keys.add(key);
                long started = System.nanoTime();
                MockHttpServletResponse r = deposit(key, acc, "1.00");
                latenciesMs.add((System.nanoTime() - started) / 1_000_000);
                assertThat(r.getStatus()).isEqualTo(201);
            }
            replay = deposit(keys.get(0), acc, "1.00");
        } finally {
            startRedis(redis, cacheSkipWindow);
        }
        double failures = meterRegistry.counter("ledger.redis.failures").count() - failuresBefore;
        long maxMs = latenciesMs.stream().mapToLong(Long::longValue).max().orElseThrow();

        log.info("RESULT redisStopped: health={} ; 20 deposits, max {} ms, all {} ; ledger.redis.failures delta={}",
                health.getContentAsString(), maxMs, latenciesMs, failures);
        assertThat(health.getStatus()).isEqualTo(200);
        assertThat((String) JsonPath.read(health.getContentAsString(), "$.status")).isEqualTo("UP");
        assertThat((String) JsonPath.read(health.getContentAsString(), "$.components.db.status")).isEqualTo("UP");
        assertThat((String) JsonPath.read(health.getContentAsString(), "$.components.redis.status"))
                .isEqualTo("DEGRADED");
        // a stopped Redis is rejected immediately, not after a timeout per call
        assertThat(maxMs).isLessThan(1_000);
        assertThat(failures).isPositive();
        assertThat(replay.getStatus()).isEqualTo(201);
        assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true"); // from Postgres
        assertThat(jdbc.queryForObject("select balance from accounts where account_number = ?", Long.class, acc))
                .isEqualTo(2_000);
        new LedgerInvariants(jdbc).assertHealthy(List.of(acc));
    }

    @Test
    @Timeout(120)
    void cacheIsUsedAgainOnceRedisIsBack() throws Exception {
        String acc = accountService.create("Outage Recovery").accountNumber();
        stopRedis();
        try {
            assertThat(deposit("down-" + UUID.randomUUID(), acc, "1.00").getStatus()).isEqualTo(201);
        } finally {
            startRedis(redis, cacheSkipWindow);
        }

        String key = "back-" + UUID.randomUUID();
        assertThat(deposit(key, acc, "1.00").getStatus()).isEqualTo(201);

        assertThat(redis.opsForValue().get("idem:" + key)).contains("\"state\":\"COMPLETED\"");
        MockHttpServletResponse health = mockMvc.perform(get("/actuator/health")).andReturn().getResponse();
        assertThat((String) JsonPath.read(health.getContentAsString(), "$.components.redis.status")).isEqualTo("UP");
    }

    private MockHttpServletResponse deposit(String key, String acc, String amount) throws Exception {
        return mockMvc.perform(post("/api/v1/transactions/deposit")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountNumber\":\"" + acc + "\",\"amount\":\"" + amount + "\"}"))
                .andReturn().getResponse();
    }
}

package com.bhuvan.ledger.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bhuvan.ledger.AbstractIntegrationTest;
import com.bhuvan.ledger.service.AccountService;
import com.bhuvan.ledger.service.PostingService;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Correlation ids, the logging context on real log lines, and the ledger metrics over HTTP. */
class ObservabilityApiTest extends AbstractIntegrationTest {

    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountService accountService;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private StringRedisTemplate redis;

    private final ListAppender<ILoggingEvent> postingLog = new ListAppender<>() {
        @Override
        protected void append(ILoggingEvent event) {
            // capture the MDC now; by the time the test reads it the request has cleared it
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };

    @BeforeEach
    void captureLogs() {
        postingLog.start();
        ((Logger) LoggerFactory.getLogger(PostingService.class)).addAppender(postingLog);
    }

    @AfterEach
    void stopCapturing() {
        ((Logger) LoggerFactory.getLogger(PostingService.class)).detachAppender(postingLog);
    }

    @Test
    void suppliedCorrelationIdIsEchoedAndOnThePostingLogLine() throws Exception {
        String acc = newAccount();
        String k = key();
        String body = deposit(k, acc, "trace-abc.123")
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Correlation-Id", "trace-abc.123"))
                .andReturn().getResponse().getContentAsString();
        String txnId = JsonPath.read(body, "$.transactionId");

        Map<String, String> mdc = mdcOf("Posted DEPOSIT txnId=" + txnId);
        assertThat(mdc).containsEntry("correlationId", "trace-abc.123")
                .containsEntry("idempotencyKey", k)
                .containsEntry("txnId", txnId);
        assertThat(MDC.getCopyOfContextMap()).as("nothing left on the thread after the request")
                .isNullOrEmpty();
    }

    @Test
    void rejectionLogLineHasTheKeyButNoTransactionId() throws Exception {
        String acc = newAccount();
        String k = key();
        mockMvc.perform(post("/api/v1/transactions/withdraw")
                        .header("Idempotency-Key", k)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cashBody(acc, "5.00")))
                .andExpect(status().isUnprocessableEntity());

        Map<String, String> mdc = mdcOf("Rejected WITHDRAWAL key=" + k);
        assertThat(mdc).containsEntry("idempotencyKey", k).containsKey("correlationId").doesNotContainKey("txnId");
    }

    @Test
    void missingCorrelationIdGetsAFreshOne() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/SYS-CASH-0001"))
                .andExpect(header().string("X-Correlation-Id", org.hamcrest.Matchers.matchesPattern(UUID_PATTERN)));
    }

    @Test
    void unsafeCorrelationIdIsReplacedNotLogged() throws Exception {
        for (String unsafe : List.of("has spaces", "line\rbreak", "x".repeat(65), "semi;colon", "")) {
            String returned = mockMvc.perform(get("/api/v1/accounts/SYS-CASH-0001")
                            .header("X-Correlation-Id", unsafe))
                    .andReturn().getResponse().getHeader("X-Correlation-Id");
            assertThat(returned).as("for %s", unsafe).matches(UUID_PATTERN);
        }
    }

    @Test
    void postingMetricsAndReplaySourcesAreCountedEndToEnd() throws Exception {
        String acc = newAccount();
        String k = key();
        double created = postings("DEPOSIT", "created");
        double replayed = postings("DEPOSIT", "replayed");
        double rejected = postings("WITHDRAWAL", "rejected");
        double fromRedis = replays("redis");
        double fromDb = replays("db");

        deposit(k, acc, null).andExpect(status().isCreated());
        deposit(k, acc, null).andExpect(status().isCreated()).andExpect(header().string("Idempotent-Replayed",
                "true"));
        redis.delete("idem:" + k); // as if the 24h Redis entry had expired: Postgres answers
        deposit(k, acc, null).andExpect(status().isCreated()).andExpect(header().string("Idempotent-Replayed",
                "true"));
        mockMvc.perform(post("/api/v1/transactions/withdraw")
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content(cashBody(acc, "500.00"))).andExpect(status().isUnprocessableEntity());

        assertThat(postings("DEPOSIT", "created") - created).isEqualTo(1);
        assertThat(postings("DEPOSIT", "replayed") - replayed).isEqualTo(2);
        assertThat(postings("WITHDRAWAL", "rejected") - rejected).isEqualTo(1);
        assertThat(replays("redis") - fromRedis).isEqualTo(1);
        assertThat(replays("db") - fromDb).isEqualTo(1);
    }

    @Test
    void ledgerMetricsAreExposedOnTheActuator() throws Exception {
        deposit(key(), newAccount(), null).andExpect(status().isCreated());

        mockMvc.perform(get("/actuator/metrics/ledger.postings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableTags[*].tag",
                        org.hamcrest.Matchers.containsInAnyOrder("type", "result")));
        mockMvc.perform(get("/actuator/metrics/ledger.postings").param("tag", "type:DEPOSIT")
                        .param("tag", "result:created"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurements[0].value", org.hamcrest.Matchers.greaterThanOrEqualTo(1.0)));
    }

    // ---------- helpers ----------

    private ResultActions deposit(String key, String acc, String correlationId) throws Exception {
        var request = post("/api/v1/transactions/deposit")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(cashBody(acc, "100.00"));
        if (correlationId != null) {
            request.header("X-Correlation-Id", correlationId);
        }
        return mockMvc.perform(request);
    }

    private Map<String, String> mdcOf(String messagePrefix) {
        return postingLog.list.stream()
                .filter(e -> e.getFormattedMessage().startsWith(messagePrefix))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no log line starting with: " + messagePrefix))
                .getMDCPropertyMap();
    }

    private double postings(String type, String result) {
        Counter c = meterRegistry.find("ledger.postings").tags("type", type, "result", result).counter();
        return c == null ? 0 : c.count();
    }

    private double replays(String source) {
        Counter c = meterRegistry.find("ledger.idempotency.replays").tag("source", source).counter();
        return c == null ? 0 : c.count();
    }

    private static String cashBody(String account, String amount) {
        return "{\"accountNumber\":\"" + account + "\",\"amount\":\"" + amount + "\"}";
    }

    private String newAccount() {
        return accountService.create("Observability Test").accountNumber();
    }

    private static String key() {
        return "ob-" + UUID.randomUUID();
    }
}

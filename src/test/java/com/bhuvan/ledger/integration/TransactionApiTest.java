package com.bhuvan.ledger.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhuvan.ledger.AbstractIntegrationTest;
import com.bhuvan.ledger.entity.AccountStatus;
import com.bhuvan.ledger.service.AccountService;
import com.bhuvan.ledger.util.Money;
import com.bhuvan.ledger.util.RequestHasher;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

class TransactionApiTest extends AbstractIntegrationTest {

    private static final String SYSTEM_ACCOUNT = "SYS-CASH-0001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountService accountService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MeterRegistry meterRegistry;

    // ---------- happy paths ----------

    @Test
    void depositCreditsAccountAndDebitsSystemAccount() throws Exception {
        String acc = newAccount();
        long systemBefore = balancePaise(SYSTEM_ACCOUNT);

        String body = deposit(key(), acc, "5000.00", "salary")
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Location", containsString("/api/v1/transactions/")))
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.type").value("DEPOSIT"))
                .andExpect(jsonPath("$.amount").value("5000.00"))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.fromAccount").value(SYSTEM_ACCOUNT))
                .andExpect(jsonPath("$.toAccount").value(acc))
                .andExpect(jsonPath("$.toBalanceAfter").value("5000.00"))
                .andExpect(jsonPath("$.reversesTransactionId").isEmpty())
                .andExpect(jsonPath("$.createdAt").exists())
                .andReturn().getResponse().getContentAsString();

        assertThat(balancePaise(acc)).isEqualTo(500_000);
        assertThat(balancePaise(SYSTEM_ACCOUNT)).isEqualTo(systemBefore - 500_000);
        assertThat((String) JsonPath.read(body, "$.fromBalanceAfter")).isEqualTo(format(systemBefore - 500_000));
    }

    @Test
    void withdrawDebitsAccountAndCreditsSystemAccount() throws Exception {
        String acc = newAccountWith("1000.00");

        withdraw(key(), acc, "200.50")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("WITHDRAWAL"))
                .andExpect(jsonPath("$.fromAccount").value(acc))
                .andExpect(jsonPath("$.toAccount").value(SYSTEM_ACCOUNT))
                .andExpect(jsonPath("$.fromBalanceAfter").value("799.50"));

        assertThat(balancePaise(acc)).isEqualTo(79_950);
    }

    @Test
    void withdrawingTheWholeBalanceLeavesZero() throws Exception {
        String acc = newAccountWith("10.00");
        withdraw(key(), acc, "10").andExpect(status().isCreated())
                .andExpect(jsonPath("$.fromBalanceAfter").value("0.00"));
    }

    @Test
    void transferMovesMoneyAndReportsBothBalances() throws Exception {
        String from = newAccountWith("5000.00");
        String to = newAccountWith("8000.00");

        transfer(key(), from, to, "1000", "INR", "rent")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("TRANSFER"))
                .andExpect(jsonPath("$.amount").value("1000.00"))
                .andExpect(jsonPath("$.fromAccount").value(from))
                .andExpect(jsonPath("$.toAccount").value(to))
                .andExpect(jsonPath("$.fromBalanceAfter").value("4000.00"))
                .andExpect(jsonPath("$.toBalanceAfter").value("9000.00"));

        assertThat(balancePaise(from)).isEqualTo(400_000);
        assertThat(balancePaise(to)).isEqualTo(900_000);
    }

    @Test
    void transferWithoutCurrencyDefaultsToInr() throws Exception {
        String from = newAccountWith("100.00");
        String to = newAccount();
        postJson("/transfer", key(), "{\"fromAccount\":\"" + from + "\",\"toAccount\":\"" + to
                + "\",\"amount\":\"1.00\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currency").value("INR"));
    }

    @Test
    void transferWorksInBothIdOrders() throws Exception {
        // lock order is by id, not by request order; both directions must post correctly
        String older = newAccountWith("100.00");
        String newer = newAccountWith("100.00");
        transfer(key(), newer, older, "30.00", "INR", null).andExpect(status().isCreated());
        transfer(key(), older, newer, "10.00", "INR", null).andExpect(status().isCreated());
        assertThat(balancePaise(older)).isEqualTo(12_000);
        assertThat(balancePaise(newer)).isEqualTo(8_000);
    }

    @Test
    void amountExactlyAtTheLimitIsAccepted() throws Exception {
        String acc = newAccount();
        deposit(key(), acc, "1000000.00", null).andExpect(status().isCreated());
        assertThat(balancePaise(acc)).isEqualTo(100_000_000);
    }

    @Test
    void getTransactionReturnsItWithBothEntries() throws Exception {
        String from = newAccountWith("500.00");
        String to = newAccount();
        String txnId = JsonPath.read(transfer(key(), from, to, "120.25", "INR", "dinner")
                .andReturn().getResponse().getContentAsString(), "$.transactionId");

        mockMvc.perform(get("/api/v1/transactions/" + txnId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value(txnId))
                .andExpect(jsonPath("$.type").value("TRANSFER"))
                .andExpect(jsonPath("$.amount").value("120.25"))
                .andExpect(jsonPath("$.description").value("dinner"))
                .andExpect(jsonPath("$.fromAccount").value(from))
                .andExpect(jsonPath("$.toAccount").value(to))
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.entries[0].accountNumber").value(from))
                .andExpect(jsonPath("$.entries[0].amount").value("120.25"))
                .andExpect(jsonPath("$.entries[0].balanceAfter").value("379.75"))
                .andExpect(jsonPath("$.entries[1].direction").value("CREDIT"))
                .andExpect(jsonPath("$.entries[1].accountNumber").value(to))
                .andExpect(jsonPath("$.entries[1].balanceAfter").value("120.25"));
    }

    @Test
    void ledgerRowsMatchThePostedTransfer() throws Exception {
        String from = newAccountWith("300.00");
        String to = newAccount();
        String k = key();
        String txnId = JsonPath.read(transfer(k, from, to, "100.00", "INR", null)
                .andReturn().getResponse().getContentAsString(), "$.transactionId");

        assertThat(jdbc.queryForObject("select idempotency_key from transactions where id = ?::uuid",
                String.class, txnId)).isEqualTo(k);
        assertThat(jdbc.queryForObject("select status || ':' || http_status || ':' || transaction_id "
                + "from idempotency_keys where idem_key = ?", String.class, k))
                .isEqualTo("COMPLETED:201:" + txnId);
        assertThat(jdbc.queryForObject("select count(*) from ledger_entries where transaction_id = ?::uuid",
                Integer.class, txnId)).isEqualTo(2);
    }

    @Test
    void descriptionIsCleanedOfControlCharacters() throws Exception {
        String acc = newAccount();
        String txnId = JsonPath.read(deposit(key(), acc, "1.00", "  pay\\u0007day\\n  ")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.transactionId");
        mockMvc.perform(get("/api/v1/transactions/" + txnId)).andExpect(jsonPath("$.description").value("payday"));
    }

    // ---------- idempotency ----------

    @Test
    void sameKeyTwiceReplaysTheSameBodyAndPostsOnce() throws Exception {
        String acc = newAccount();
        String k = key();
        String first = deposit(k, acc, "50.00", null).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String second = deposit(k, acc, "50", null)   // "50" and "50.00" are the same request
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        assertThat(transactionsWithKey(k)).isEqualTo(1);
        assertThat(balancePaise(acc)).isEqualTo(5_000);
    }

    @Test
    void sameKeyWithDifferentAmountIsRejectedAndNotStored() throws Exception {
        String acc = newAccount();
        String k = key();
        deposit(k, acc, "50.00", null).andExpect(status().isCreated());
        String storedHash = storedHash(k);

        deposit(k, acc, "60.00", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(storedHash(k)).isEqualTo(storedHash);
        assertThat(balancePaise(acc)).isEqualTo(5_000);
    }

    @Test
    void sameKeyOnADifferentEndpointIsRejected() throws Exception {
        String acc = newAccountWith("100.00");
        String k = key();
        deposit(k, acc, "10.00", null).andExpect(status().isCreated());
        withdraw(k, acc, "10.00", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(balancePaise(acc)).isEqualTo(11_000);
    }

    @Test
    void businessRejectionIsStoredAndReplayedEvenAfterTopUp() throws Exception {
        String acc = newAccountWith("10.00");
        String k = key();
        String first = withdraw(k, acc, "50.00")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.accountNumber").value(acc))
                .andReturn().getResponse().getContentAsString();

        deposit(key(), acc, "100.00", null).andExpect(status().isCreated());

        // same key: same answer, even though the account could now pay
        String replay = withdraw(k, acc, "50.00")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);
        assertThat(jdbc.queryForObject("select status || ':' || http_status from idempotency_keys where idem_key = ?",
                String.class, k)).isEqualTo("COMPLETED:422");

        // a new key is a new request
        withdraw(key(), acc, "50.00").andExpect(status().isCreated());
    }

    @Test
    void committedInProgressRowGives409WithRetryAfter() throws Exception {
        // The app never commits an IN_PROGRESS row (claim and completion share one DB transaction),
        // so plant one directly to prove the guard answers 409 instead of posting twice.
        String acc = newAccount();
        String k = key();
        String hash = RequestHasher.hash("DEPOSIT", SYSTEM_ACCOUNT, acc, 100, "INR", null, null);
        jdbc.update("insert into idempotency_keys (idem_key, request_hash, operation, status) "
                + "values (?, ?, 'DEPOSIT', 'IN_PROGRESS')", k, hash);

        deposit(k, acc, "1.00", null)
                .andExpect(status().isConflict())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("REQUEST_IN_PROGRESS"));
        assertThat(transactionsWithKey(k)).isZero();
        assertThat(balancePaise(acc)).isZero();
    }

    @Test
    void missingIdempotencyKeyIs400() throws Exception {
        String acc = newAccount();
        mockMvc.perform(post("/api/v1/transactions/deposit").contentType(MediaType.APPLICATION_JSON)
                        .content(cashBody(acc, "1.00", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));
        deposit("   ", acc, "1.00", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));
        assertThat(balancePaise(acc)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "has space in it", "has.dot.inside", "emoji-é-key-x",
            "a123456789012345678901234567890123456789012345678901234567890xyzw"})
    void invalidIdempotencyKeyIs400AndNothingIsStored(String badKey) throws Exception {
        String acc = newAccount();
        deposit(badKey, acc, "1.00", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
        assertThat(jdbc.queryForObject("select count(*) from idempotency_keys where idem_key = ?",
                Integer.class, badKey)).isZero();
    }

    @Test
    void keyOfExactly8And64CharactersIsAccepted() throws Exception {
        String acc = newAccount();
        deposit("abcd-12_", acc, "1.00", null).andExpect(status().isCreated());
        String key64 = "k".repeat(28) + UUID.randomUUID(); // 28 + 36
        assertThat(key64).hasSize(64);
        deposit(key64, acc, "1.00", null).andExpect(status().isCreated());
    }

    // ---------- 400 validation (never stored) ----------

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-5", "-0", "10.001", "1e3", "1,000", " 10", "10 ", "NaN", "",
            "123456789", ".5", "5."})
    void invalidAmountIs400(String amount) throws Exception {
        String acc = newAccount();
        String k = key();
        deposit(k, acc, amount, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("amount"));
        assertThat(jdbc.queryForObject("select count(*) from idempotency_keys where idem_key = ?",
                Integer.class, k)).isZero();
    }

    @Test
    void missingAmountIs400() throws Exception {
        postJson("/deposit", key(), "{\"accountNumber\":\"" + newAccount() + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].message").value("amount is required"));
    }

    @Test
    void amountAsJsonNumberIs400() throws Exception {
        postJson("/deposit", key(), "{\"accountNumber\":\"" + newAccount() + "\",\"amount\":1000.00}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.detail").value("Field 'amount' must be a JSON string"));
    }

    @Test
    void missingAccountFieldsAre400() throws Exception {
        postJson("/transfer", key(), "{\"amount\":\"1.00\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(2));
    }

    @Test
    void descriptionOver255CharactersIs400() throws Exception {
        deposit(key(), newAccount(), "1.00", "x".repeat(256))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        deposit(key(), newAccount(), "1.00", "x".repeat(255)).andExpect(status().isCreated());
    }

    @Test
    void unknownFieldIs400() throws Exception {
        postJson("/deposit", key(), "{\"accountNumber\":\"" + newAccount() + "\",\"ammount\":\"1.00\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.detail").value("Unknown field 'ammount'"));
    }

    @Test
    void malformedJsonIs400() throws Exception {
        postJson("/transfer", key(), "{\"fromAccount\": ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(content().string(not(containsString("Exception"))));
    }

    @Test
    void wrongContentTypeIs415() throws Exception {
        mockMvc.perform(post("/api/v1/transactions/deposit").header("Idempotency-Key", key())
                        .contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void getWithMalformedTransactionIdIs400() throws Exception {
        mockMvc.perform(get("/api/v1/transactions/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void getUnknownTransactionIs404() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/transactions/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_FOUND"))
                .andExpect(jsonPath("$.transactionId").value(id.toString()));
    }

    // ---------- business rejections (stored as COMPLETED) ----------

    @Test
    void depositToUnknownAccountIs404AndStored() throws Exception {
        String k = key();
        String first = deposit(k, "ACC9999999999", "1.00", null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"))
                .andExpect(jsonPath("$.accountNumber").value("ACC9999999999"))
                .andReturn().getResponse().getContentAsString();
        deposit(k, "ACC9999999999", "1.00", null)
                .andExpect(status().isNotFound())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(content().string(first));
    }

    @Test
    void transferWithUnknownSourceOrDestinationIs404() throws Exception {
        String acc = newAccountWith("10.00");
        transfer(key(), "ACC9999999998", acc, "1.00", "INR", null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.accountNumber").value("ACC9999999998"));
        transfer(key(), acc, "ACC9999999997", "1.00", "INR", null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.accountNumber").value("ACC9999999997"));
        assertThat(balancePaise(acc)).isEqualTo(1_000);
    }

    @Test
    void transferToSelfIs422() throws Exception {
        String acc = newAccountWith("10.00");
        transfer(key(), acc, acc, "1.00", "INR", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SAME_ACCOUNT_TRANSFER"));
    }

    @Test
    void insufficientFundsIs422AndNothingMoves() throws Exception {
        String from = newAccountWith("10.00");
        String to = newAccount();
        transfer(key(), from, to, "10.01", "INR", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
        assertThat(balancePaise(from)).isEqualTo(1_000);
        assertThat(balancePaise(to)).isZero();
    }

    @Test
    void frozenSourceOrDestinationIs422() throws Exception {
        String frozen = newAccountWith("100.00");
        String other = newAccountWith("100.00");
        accountService.changeStatus(frozen, AccountStatus.FROZEN);

        transfer(key(), frozen, other, "1.00", "INR", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"))
                .andExpect(jsonPath("$.accountNumber").value(frozen))
                .andExpect(jsonPath("$.status").value("FROZEN"));
        transfer(key(), other, frozen, "1.00", "INR", null)
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"))
                .andExpect(jsonPath("$.accountNumber").value(frozen));
        deposit(key(), frozen, "1.00", null).andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));
        withdraw(key(), frozen, "1.00").andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));

        accountService.changeStatus(frozen, AccountStatus.ACTIVE);
        transfer(key(), frozen, other, "1.00", "INR", null).andExpect(status().isCreated());
    }

    @Test
    void closedAccountIs422() throws Exception {
        String closed = newAccount();
        accountService.changeStatus(closed, AccountStatus.CLOSED);
        deposit(key(), closed, "1.00", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"))
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void systemAccountCannotBeUsedThroughUserEndpoints() throws Exception {
        String acc = newAccountWith("10.00");
        long systemBefore = balancePaise(SYSTEM_ACCOUNT);

        deposit(key(), SYSTEM_ACCOUNT, "1.00", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SYSTEM_ACCOUNT_IMMUTABLE"));
        withdraw(key(), SYSTEM_ACCOUNT, "1.00")
                .andExpect(jsonPath("$.code").value("SYSTEM_ACCOUNT_IMMUTABLE"));
        transfer(key(), SYSTEM_ACCOUNT, acc, "1.00", "INR", null)
                .andExpect(jsonPath("$.code").value("SYSTEM_ACCOUNT_IMMUTABLE"));
        transfer(key(), acc, SYSTEM_ACCOUNT, "1.00", "INR", null)
                .andExpect(jsonPath("$.code").value("SYSTEM_ACCOUNT_IMMUTABLE"));

        assertThat(balancePaise(SYSTEM_ACCOUNT)).isEqualTo(systemBefore);
    }

    @Test
    void nonInrCurrencyIs422() throws Exception {
        String from = newAccountWith("10.00");
        transfer(key(), from, newAccount(), "1.00", "USD", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CURRENCY_NOT_SUPPORTED"))
                .andExpect(jsonPath("$.currency").value("USD"));
    }

    @Test
    void amountAboveTheLimitIs422() throws Exception {
        deposit(key(), newAccount(), "1000000.01", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AMOUNT_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.limit").value("1000000.00"));
    }

    // ---------- 503 / 500 (never stored) ----------

    @Test
    @Timeout(60)
    void lockHeldTooLongGives503AfterRetriesAndTheSameKeyWorksLater() throws Exception {
        String acc = newAccountWith("100.00");
        String to = newAccount();
        String k = key();
        double retriesBefore = meterRegistry.counter("ledger.lock.retries").count();

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement ps = holder.prepareStatement(
                    "select id from accounts where account_number = ? for update")) {
                ps.setString(1, acc);
                ps.executeQuery();
            }

            long started = System.nanoTime();
            transfer(k, acc, to, "1.00", "INR", null)
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "1"))
                    .andExpect(jsonPath("$.code").value("SYSTEM_BUSY"));
            long waitedMs = (System.nanoTime() - started) / 1_000_000;
            // 3 attempts x 2s lock_timeout plus backoff
            assertThat(waitedMs).isGreaterThanOrEqualTo(5_500);
            holder.rollback();
        }

        assertThat(meterRegistry.counter("ledger.lock.retries").count() - retriesBefore).isEqualTo(2.0);
        assertThat(jdbc.queryForObject("select count(*) from idempotency_keys where idem_key = ?",
                Integer.class, k)).isZero();

        transfer(k, acc, to, "1.00", "INR", null)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));
    }

    @Test
    void balanceOverflowIs500WithCorrelationIdAndNothingIsWritten() throws Exception {
        String acc = newAccount();
        String k = key();
        jdbc.update("update accounts set balance = ? where account_number = ?", Long.MAX_VALUE - 50, acc);
        try {
            deposit(k, acc, "1.00", null)
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.correlationId").exists())
                    .andExpect(content().string(not(containsString("Arithmetic"))));
            assertThat(transactionsWithKey(k)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from idempotency_keys where idem_key = ?",
                    Integer.class, k)).isZero();
        } finally {
            jdbc.update("update accounts set balance = 0 where account_number = ?", acc);
        }
    }

    // ---------- helpers ----------

    private static String key() {
        return "k-" + UUID.randomUUID();
    }

    private String newAccount() {
        return accountService.create("Test Owner").accountNumber();
    }

    private String newAccountWith(String amount) throws Exception {
        String acc = newAccount();
        deposit(key(), acc, amount, null).andExpect(status().isCreated());
        return acc;
    }

    private ResultActions deposit(String key, String account, String amount, String description) throws Exception {
        return postJson("/deposit", key, cashBody(account, amount, description));
    }

    private ResultActions withdraw(String key, String account, String amount) throws Exception {
        return withdraw(key, account, amount, null);
    }

    private ResultActions withdraw(String key, String account, String amount, String description) throws Exception {
        return postJson("/withdraw", key, cashBody(account, amount, description));
    }

    private ResultActions transfer(String key, String from, String to, String amount, String currency,
            String description) throws Exception {
        return postJson("/transfer", key, "{\"fromAccount\":\"" + from + "\",\"toAccount\":\"" + to
                + "\",\"amount\":\"" + amount + "\",\"currency\":\"" + currency + "\""
                + (description == null ? "" : ",\"description\":\"" + description + "\"") + "}");
    }

    private static String cashBody(String account, String amount, String description) {
        return "{\"accountNumber\":\"" + account + "\",\"amount\":\"" + amount + "\""
                + (description == null ? "" : ",\"description\":\"" + description + "\"") + "}";
    }

    private ResultActions postJson(String path, String key, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/transactions" + path)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private long balancePaise(String accountNumber) {
        return jdbc.queryForObject("select balance from accounts where account_number = ?", Long.class, accountNumber);
    }

    private int transactionsWithKey(String key) {
        return jdbc.queryForObject("select count(*) from transactions where idempotency_key = ?", Integer.class, key);
    }

    private String storedHash(String key) {
        return jdbc.queryForObject("select request_hash from idempotency_keys where idem_key = ?", String.class, key);
    }

    private static String format(long paise) {
        return Money.format(paise);
    }
}

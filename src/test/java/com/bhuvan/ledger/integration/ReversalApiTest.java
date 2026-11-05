package com.bhuvan.ledger.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhuvan.ledger.AbstractIntegrationTest;
import com.bhuvan.ledger.LedgerInvariants;
import com.bhuvan.ledger.entity.AccountStatus;
import com.bhuvan.ledger.service.AccountService;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

class ReversalApiTest extends AbstractIntegrationTest {

    private static final String SYSTEM_ACCOUNT = "SYS-CASH-0001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountService accountService;

    @Autowired
    private JdbcTemplate jdbc;

    private LedgerInvariants ledger;

    @BeforeEach
    void setUp() {
        ledger = new LedgerInvariants(jdbc);
    }

    // ---------- happy paths ----------

    @Test
    void reversingATransferSwapsTheAccountsAndRestoresBothBalances() throws Exception {
        String from = newAccountWith("500.00");
        String to = newAccountWith("100.00");
        String original = idOf(transfer(key(), from, to, "300.00"));

        String body = reverse(key(), original, "{\"reason\":\"customer dispute\"}")
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andReturn().getResponse().getContentAsString();

        String reversalId = JsonPath.read(body, "$.transactionId");
        assertThat(reversalId).isNotEqualTo(original);
        assertThat((String) JsonPath.read(body, "$.type")).isEqualTo("REVERSAL");
        assertThat((String) JsonPath.read(body, "$.amount")).isEqualTo("300.00");
        assertThat((String) JsonPath.read(body, "$.fromAccount")).isEqualTo(to);
        assertThat((String) JsonPath.read(body, "$.toAccount")).isEqualTo(from);
        assertThat((String) JsonPath.read(body, "$.fromBalanceAfter")).isEqualTo("100.00");
        assertThat((String) JsonPath.read(body, "$.toBalanceAfter")).isEqualTo("500.00");
        assertThat((String) JsonPath.read(body, "$.reversesTransactionId")).isEqualTo(original);
        assertThat(ledger.balance(from)).isEqualTo(50_000);
        assertThat(ledger.balance(to)).isEqualTo(10_000);

        mockMvc.perform(get("/api/v1/transactions/" + reversalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("REVERSAL"))
                .andExpect(jsonPath("$.description").value("customer dispute"))
                .andExpect(jsonPath("$.reversesTransactionId").value(original))
                .andExpect(jsonPath("$.entries[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.entries[0].accountNumber").value(to))
                .andExpect(jsonPath("$.entries[0].balanceAfter").value("100.00"))
                .andExpect(jsonPath("$.entries[1].direction").value("CREDIT"))
                .andExpect(jsonPath("$.entries[1].accountNumber").value(from))
                .andExpect(jsonPath("$.entries[1].balanceAfter").value("500.00"));
        // the original is untouched: reversals add rows, they never edit history
        mockMvc.perform(get("/api/v1/transactions/" + original))
                .andExpect(jsonPath("$.type").value("TRANSFER"))
                .andExpect(jsonPath("$.reversesTransactionId").isEmpty())
                .andExpect(jsonPath("$.entries[0].accountNumber").value(from));
        ledger.assertHealthy(List.of(from, to));
    }

    @Test
    void reversalResponseCarriesALocationForTheNewTransaction() throws Exception {
        String acc = newAccountWith("10.00");
        String original = idOf(withdraw(key(), acc, "4.00"));

        var response = reverse(key(), original, null).andExpect(status().isCreated()).andReturn().getResponse();

        String reversalId = JsonPath.read(response.getContentAsString(), "$.transactionId");
        assertThat(response.getHeader("Location")).isEqualTo("/api/v1/transactions/" + reversalId);
    }

    @Test
    void reversingADepositTakesTheMoneyBackToTheSystemAccount() throws Exception {
        String acc = newAccount();
        String original = idOf(deposit(key(), acc, "250.00"));
        long systemBefore = ledger.balance(SYSTEM_ACCOUNT);

        reverse(key(), original, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fromAccount").value(acc))
                .andExpect(jsonPath("$.toAccount").value(SYSTEM_ACCOUNT))
                .andExpect(jsonPath("$.fromBalanceAfter").value("0.00"));

        assertThat(ledger.balance(acc)).isZero();
        assertThat(ledger.balance(SYSTEM_ACCOUNT)).isEqualTo(systemBefore + 25_000);
        ledger.assertHealthy(List.of(acc));
    }

    @Test
    void reversingAWithdrawalPutsTheMoneyBack() throws Exception {
        String acc = newAccountWith("100.00");
        String original = idOf(withdraw(key(), acc, "100.00"));

        reverse(key(), original, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fromAccount").value(SYSTEM_ACCOUNT))
                .andExpect(jsonPath("$.toAccount").value(acc))
                .andExpect(jsonPath("$.toBalanceAfter").value("100.00"));
        ledger.assertHealthy(List.of(acc));
    }

    @Test
    void reversalWithoutABodyOrContentTypeWorks() throws Exception {
        String acc = newAccountWith("10.00");
        String original = idOf(withdraw(key(), acc, "1.00"));

        mockMvc.perform(post("/api/v1/transactions/" + original + "/reverse").header("Idempotency-Key", key()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("REVERSAL"));
    }

    @Test
    void reasonIsCleanedOfControlCharacters() throws Exception {
        String acc = newAccountWith("10.00");
        String original = idOf(withdraw(key(), acc, "1.00"));

        String id = idOf(reverse(key(), original, "{\"reason\":\"  bank\\u0007 error \\n\"}"));
        mockMvc.perform(get("/api/v1/transactions/" + id)).andExpect(jsonPath("$.description").value("bank error"));
    }

    // ---------- business rules ----------

    @Test
    void secondReversalIs409AndIsStoredAgainstItsKey() throws Exception {
        String acc = newAccountWith("50.00");
        String original = idOf(withdraw(key(), acc, "20.00"));
        reverse(key(), original, null).andExpect(status().isCreated());

        String second = key();
        String body = reverse(second, original, null)
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(jsonPath("$.code").value("ALREADY_REVERSED"))
                .andExpect(jsonPath("$.transactionId").value(original))
                .andReturn().getResponse().getContentAsString();

        // a business answer: replayed byte for byte for the same key
        reverse(second, original, null)
                .andExpect(status().isConflict())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(content().string(body));
        assertThat(reversalsOf(original)).isEqualTo(1);
        assertThat(ledger.balance(acc)).isEqualTo(5_000);
    }

    @Test
    void reversingAReversalIs422() throws Exception {
        String acc = newAccountWith("50.00");
        String original = idOf(withdraw(key(), acc, "20.00"));
        String reversal = idOf(reverse(key(), original, null));

        reverse(key(), reversal, null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CANNOT_REVERSE_REVERSAL"))
                .andExpect(jsonPath("$.transactionId").value(reversal));
        assertThat(ledger.balance(acc)).isEqualTo(5_000);
    }

    @Test
    void reversingADepositAfterTheMoneyWasSpentIs422AndNothingMoves() throws Exception {
        String acc = newAccount();
        String other = newAccount();
        String original = idOf(deposit(key(), acc, "100.00"));
        transfer(key(), acc, other, "60.00").andExpect(status().isCreated());

        reverse(key(), original, null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.accountNumber").value(acc));

        assertThat(ledger.balance(acc)).isEqualTo(4_000);
        assertThat(reversalsOf(original)).isZero();
        // still reversible later, with a new key, once the funds are back
        deposit(key(), acc, "60.00").andExpect(status().isCreated());
        reverse(key(), original, null).andExpect(status().isCreated());
        assertThat(ledger.balance(acc)).isZero();
        ledger.assertHealthy(List.of(acc, other));
    }

    @Test
    void reversalTouchingAFrozenAccountIs422UntilItIsUnfrozen() throws Exception {
        String from = newAccountWith("100.00");
        String to = newAccount();
        String original = idOf(transfer(key(), from, to, "30.00"));

        for (String frozen : List.of(from, to)) {
            accountService.changeStatus(frozen, AccountStatus.FROZEN);
            reverse(key(), original, null)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"))
                    .andExpect(jsonPath("$.accountNumber").value(frozen))
                    .andExpect(jsonPath("$.status").value("FROZEN"));
            accountService.changeStatus(frozen, AccountStatus.ACTIVE);
        }

        reverse(key(), original, null).andExpect(status().isCreated());
        assertThat(ledger.balance(from)).isEqualTo(10_000);
        assertThat(ledger.balance(to)).isZero();
    }

    @Test
    void reversalIntoAClosedAccountIs422() throws Exception {
        String acc = newAccountWith("10.00");
        String original = idOf(withdraw(key(), acc, "10.00"));
        accountService.changeStatus(acc, AccountStatus.CLOSED);

        reverse(key(), original, null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"))
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void reversingAnUnknownTransactionIs404AndStored() throws Exception {
        String missing = UUID.randomUUID().toString();
        String k = key();

        reverse(k, missing, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_FOUND"))
                .andExpect(jsonPath("$.transactionId").value(missing));
        reverse(k, missing, null)
                .andExpect(status().isNotFound())
                .andExpect(header().string("Idempotent-Replayed", "true"));
    }

    // ---------- idempotency and validation ----------

    @Test
    void sameKeyTwiceReplaysAndReversesOnce() throws Exception {
        String acc = newAccountWith("10.00");
        String original = idOf(withdraw(key(), acc, "5.00"));
        String k = key();

        String first = reverse(k, original, "{\"reason\":\"dup\"}").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        reverse(k, original, "{\"reason\":\"dup\"}")
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(content().string(first));
        assertThat(reversalsOf(original)).isEqualTo(1);
        assertThat(ledger.balance(acc)).isEqualTo(1_000);
    }

    @Test
    void sameKeyWithADifferentReasonOrTransactionOrEndpointIsRejected() throws Exception {
        String acc = newAccountWith("10.00");
        String original = idOf(withdraw(key(), acc, "5.00"));
        String other = idOf(withdraw(key(), acc, "1.00"));
        String k = key();
        reverse(k, original, "{\"reason\":\"a\"}").andExpect(status().isCreated());

        reverse(k, original, "{\"reason\":\"b\"}").andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        reverse(k, other, "{\"reason\":\"a\"}").andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        withdraw(k, acc, "5.00").andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(reversalsOf(other)).isZero();
    }

    @Test
    void missingKeyIs400AndNothingIsReversed() throws Exception {
        String acc = newAccountWith("10.00");
        String original = idOf(withdraw(key(), acc, "5.00"));

        mockMvc.perform(post("/api/v1/transactions/" + original + "/reverse")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));
        assertThat(reversalsOf(original)).isZero();
    }

    @Test
    void malformedTransactionIdIs400() throws Exception {
        reverse(key(), "not-a-uuid", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void reasonOver255CharactersIs400() throws Exception {
        reverse(key(), UUID.randomUUID().toString(), "{\"reason\":\"" + "x".repeat(256) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("reason"));
    }

    @Test
    void unknownFieldIs400() throws Exception {
        reverse(key(), UUID.randomUUID().toString(), "{\"reson\":\"typo\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void nonJsonBodyIs415() throws Exception {
        mockMvc.perform(post("/api/v1/transactions/" + UUID.randomUUID() + "/reverse")
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.TEXT_PLAIN).content("please reverse"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    // ---------- helpers ----------

    private ResultActions reverse(String key, String transactionId, String body) throws Exception {
        var request = post("/api/v1/transactions/" + transactionId + "/reverse").header("Idempotency-Key", key);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request);
    }

    private ResultActions deposit(String key, String acc, String amount) throws Exception {
        return postJson("/deposit", key, "{\"accountNumber\":\"" + acc + "\",\"amount\":\"" + amount + "\"}");
    }

    private ResultActions withdraw(String key, String acc, String amount) throws Exception {
        return postJson("/withdraw", key, "{\"accountNumber\":\"" + acc + "\",\"amount\":\"" + amount + "\"}");
    }

    private ResultActions transfer(String key, String from, String to, String amount) throws Exception {
        return postJson("/transfer", key, "{\"fromAccount\":\"" + from + "\",\"toAccount\":\"" + to
                + "\",\"amount\":\"" + amount + "\"}");
    }

    private ResultActions postJson(String path, String key, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/transactions" + path)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String idOf(ResultActions created) throws Exception {
        return JsonPath.read(created.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(),
                "$.transactionId");
    }

    private String newAccount() {
        return accountService.create("Reversal Test").accountNumber();
    }

    private String newAccountWith(String amount) throws Exception {
        String acc = newAccount();
        deposit(key(), acc, amount).andExpect(status().isCreated());
        return acc;
    }

    private int reversalsOf(String transactionId) {
        return jdbc.queryForObject("select count(*) from transactions where reverses_txn_id = ?::uuid",
                Integer.class, transactionId);
    }

    private static String key() {
        return "rv-" + UUID.randomUUID();
    }
}

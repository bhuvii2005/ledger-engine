package com.bhuvan.ledger.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhuvan.ledger.AbstractIntegrationTest;
import com.bhuvan.ledger.dto.ReconciliationReport;
import com.bhuvan.ledger.dto.ReconciliationReport.Check;
import com.bhuvan.ledger.entity.TransactionType;
import com.bhuvan.ledger.service.AccountService;
import com.bhuvan.ledger.service.PostingCommand;
import com.bhuvan.ledger.service.PostingResult;
import com.bhuvan.ledger.service.ReconciliationJob;
import com.bhuvan.ledger.service.ReconciliationService;
import com.bhuvan.ledger.service.TransactionFacade;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reconciliation must report a healthy ledger as healthy and catch each kind of damage.
 *
 * <p>Most damage can't be committed: the triggers and CHECK constraints refuse it, and the
 * append-only tables couldn't be cleaned up afterwards anyway. Those cases are planted inside a
 * transaction, reconciliation runs inside the same transaction (so it sees them), and then
 * everything is rolled back. The one committed corruption, a balance edited with plain SQL
 * (plan acceptance), is undone in a finally block so later tests see a healthy ledger.
 */
class ReconciliationApiTest extends AbstractIntegrationTest {

    private static final String SYSTEM_ACCOUNT = "SYS-CASH-0001";
    private static final List<String> CHECK_NAMES = List.of("SNAPSHOT_MATCHES_ENTRIES", "TRANSACTIONS_BALANCED",
            "GLOBAL_ZERO_SUM", "NO_NEGATIVE_USER_BALANCES", "LATEST_BALANCE_AFTER_MATCHES",
            "COMPLETED_KEYS_HAVE_TRANSACTIONS", "TRANSACTIONS_HAVE_KEYS");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountService accountService;

    @Autowired
    private TransactionFacade facade;

    @Autowired
    private ReconciliationService reconciliation;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ApplicationContext context;

    @Test
    void healthyLedgerIsReportedHealthyThroughTheEndpoint() throws Exception {
        // a bit of everything: deposit, transfer, withdrawal, reversal and a stored rejection
        String a = newAccount();
        String b = newAccount();
        post(TransactionType.DEPOSIT, SYSTEM_ACCOUNT, a, 50_000);
        PostingResult transfer = post(TransactionType.TRANSFER, a, b, 20_000);
        assertThat(facade.execute(PostingCommand.of(TransactionType.REVERSAL, key(), null, null, 0, "INR",
                "dispute", transfer.transactionId())).httpStatus()).isEqualTo(201);
        post(TransactionType.WITHDRAWAL, a, SYSTEM_ACCOUNT, 5_000);
        assertThat(facade.execute(PostingCommand.of(TransactionType.WITHDRAWAL, key(), b, SYSTEM_ACCOUNT,
                1_000_000, "INR", null, null)).httpStatus()).as("b is empty again").isEqualTo(422);

        String body = mockMvc.perform(get("/api/v1/admin/reconciliation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthy").value(true))
                .andExpect(jsonPath("$.totalBalance").value("0.00"))
                .andExpect(jsonPath("$.checkedAt").exists())
                .andExpect(jsonPath("$.checks.length()").value(CHECK_NAMES.size()))
                .andExpect(jsonPath("$.failedChecks").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat((List<String>) JsonPath.read(body, "$.checks[*].name")).containsExactlyElementsOf(CHECK_NAMES);
        assertThat((List<Boolean>) JsonPath.read(body, "$.checks[*].passed")).containsOnly(true);
        assertThat((List<Integer>) JsonPath.read(body, "$.checks[*].failures")).containsOnly(0);
        long transactions = ((Number) JsonPath.read(body, "$.transactions")).longValue();
        long entries = ((Number) JsonPath.read(body, "$.entries")).longValue();
        assertThat(transactions).isPositive();
        assertThat(entries).isEqualTo(2 * transactions);
    }

    @Test
    void balanceEditedWithPlainSqlIsReported() throws Exception {
        String acc = newAccount();
        post(TransactionType.DEPOSIT, SYSTEM_ACCOUNT, acc, 1_000);
        jdbc.update("update accounts set balance = balance + 1 where account_number = ?", acc);
        try {
            String body = mockMvc.perform(get("/api/v1/admin/reconciliation"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.healthy").value(false))
                    .andExpect(jsonPath("$.totalBalance").value("0.01"))
                    .andReturn().getResponse().getContentAsString();

            assertThat(check(body, "SNAPSHOT_MATCHES_ENTRIES")).isEqualTo(new Check("SNAPSHOT_MATCHES_ENTRIES",
                    false, 1, List.of(acc)));
            assertThat(check(body, "LATEST_BALANCE_AFTER_MATCHES")).isEqualTo(new Check(
                    "LATEST_BALANCE_AFTER_MATCHES", false, 1, List.of(acc)));
            assertThat(check(body, "GLOBAL_ZERO_SUM")).isEqualTo(new Check("GLOBAL_ZERO_SUM", false, 1,
                    List.of("0.01")));
            assertThat(failedNames(body)).containsExactlyInAnyOrder("SNAPSHOT_MATCHES_ENTRIES",
                    "LATEST_BALANCE_AFTER_MATCHES", "GLOBAL_ZERO_SUM");
        } finally {
            jdbc.update("update accounts set balance = balance - 1 where account_number = ?", acc);
        }
        mockMvc.perform(get("/api/v1/admin/reconciliation")).andExpect(jsonPath("$.healthy").value(true));
    }

    @Test
    void unbalancedTransactionsAreReported() {
        String a = newAccount();
        String b = newAccount();
        List<String> planted = new ArrayList<>();
        ReconciliationReport report = plantedAndRolledBack(() -> {
            // the deferred balance trigger would refuse both of these at COMMIT; there is no commit
            UUID oneLegged = rawTransaction(100);
            rawEntry(oneLegged, a, "DEBIT", 100);
            UUID wrongAmount = rawTransaction(100);
            rawEntry(wrongAmount, a, "DEBIT", 50);
            rawEntry(wrongAmount, b, "CREDIT", 50); // debit equals credit, but not the transaction's amount
            planted.add(oneLegged.toString());
            planted.add(wrongAmount.toString());
            return reconciliation.run();
        });
        assertThat(report.healthy()).isFalse();
        Check balanced = find(report, "TRANSACTIONS_BALANCED");
        assertThat(balanced.failures()).isEqualTo(2);
        assertThat(balanced.sample()).containsExactlyInAnyOrderElementsOf(planted);
        assertThat(reconciliation.run().healthy()).as("after the rollback").isTrue();
    }

    @Test
    void negativeUserBalanceIsReported() {
        String acc = newAccount();
        ReconciliationReport report = plantedAndRolledBack(() -> {
            // the CHECK constraint makes this impossible; DDL is transactional in Postgres,
            // so dropping it here is undone by the rollback
            jdbc.execute("alter table accounts drop constraint chk_user_balance_non_negative");
            jdbc.update("update accounts set balance = -100 where account_number = ?", acc);
            return reconciliation.run();
        });
        assertThat(find(report, "NO_NEGATIVE_USER_BALANCES"))
                .isEqualTo(new Check("NO_NEGATIVE_USER_BALANCES", false, 1, List.of(acc)));
        assertThat(jdbc.queryForObject("select count(*) from pg_constraint where conname = ?", Integer.class,
                "chk_user_balance_non_negative")).as("constraint restored by the rollback").isEqualTo(1);
    }

    @Test
    void completedKeyPointingAtNoTransactionIsReported() {
        String acc = newAccount();
        PostingResult deposit = post(TransactionType.DEPOSIT, SYSTEM_ACCOUNT, acc, 100);
        String depositKey = keyOf(deposit.transactionId());
        ReconciliationReport report = plantedAndRolledBack(() -> {
            jdbc.update("update idempotency_keys set transaction_id = ? where idem_key = ?", UUID.randomUUID(),
                    depositKey);
            return reconciliation.run();
        });
        assertThat(find(report, "COMPLETED_KEYS_HAVE_TRANSACTIONS").sample()).containsExactly(depositKey);
        // and from the other side, the transaction lost the key that points back at it
        assertThat(find(report, "TRANSACTIONS_HAVE_KEYS").sample())
                .containsExactly(deposit.transactionId().toString());
    }

    @Test
    void transactionWithoutAnIdempotencyRowIsReported() {
        String acc = newAccount();
        PostingResult deposit = post(TransactionType.DEPOSIT, SYSTEM_ACCOUNT, acc, 100);
        ReconciliationReport report = plantedAndRolledBack(() -> {
            jdbc.update("delete from idempotency_keys where transaction_id = ?", deposit.transactionId());
            return reconciliation.run();
        });
        assertThat(find(report, "TRANSACTIONS_HAVE_KEYS")).isEqualTo(new Check("TRANSACTIONS_HAVE_KEYS", false, 1,
                List.of(deposit.transactionId().toString())));
        assertThat(find(report, "COMPLETED_KEYS_HAVE_TRANSACTIONS").passed()).isTrue();
    }

    @Test
    void sampleIsCappedAt20ButTheCountIsExact() {
        List<String> accounts = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            accounts.add(newAccount());
        }
        ReconciliationReport report = plantedAndRolledBack(() -> {
            for (String acc : accounts) {
                jdbc.update("update accounts set balance = balance + 1 where account_number = ?", acc);
            }
            return reconciliation.run();
        });
        Check snapshot = find(report, "SNAPSHOT_MATCHES_ENTRIES");
        assertThat(snapshot.failures()).isEqualTo(25);
        assertThat(snapshot.sample()).hasSize(20).isSubsetOf(accounts);
        assertThat(report.totalBalance()).isEqualTo("0.25");
        // accounts with no entries are covered by the snapshot check, not this one
        assertThat(find(report, "LATEST_BALANCE_AFTER_MATCHES").passed()).isTrue();
    }

    @Test
    void scheduledJobIsOffInTests() {
        assertThat(context.getBeanNamesForType(ReconciliationJob.class)).isEmpty();
    }

    // ---------- helpers ----------

    private ReconciliationReport plantedAndRolledBack(Supplier<ReconciliationReport> work) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            status.setRollbackOnly();
            return work.get();
        });
    }

    private UUID rawTransaction(long amount) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into transactions (id, txn_type, amount, idempotency_key) values (?, 'DEPOSIT', ?, ?)",
                id, amount, "planted-" + id);
        return id;
    }

    private void rawEntry(UUID txnId, String accountNumber, String direction, long amount) {
        jdbc.update("""
                insert into ledger_entries (transaction_id, account_id, direction, amount, balance_after)
                select ?, id, ?, ?, 0 from accounts where account_number = ?
                """, txnId, direction, amount, accountNumber);
    }

    private PostingResult post(TransactionType type, String from, String to, long paise) {
        PostingResult result = facade.execute(PostingCommand.of(type, key(), from, to, paise, "INR", null, null));
        assertThat(result.httpStatus()).isEqualTo(201);
        return result;
    }

    private String keyOf(UUID transactionId) {
        return jdbc.queryForObject("select idempotency_key from transactions where id = ?", String.class,
                transactionId);
    }

    private static Check find(ReconciliationReport report, String name) {
        return report.checks().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    private static Check check(String body, String name) {
        String path = "$.checks[?(@.name == '" + name + "')]";
        List<Boolean> passed = JsonPath.read(body, path + ".passed");
        List<Number> failures = JsonPath.read(body, path + ".failures");
        List<List<String>> sample = JsonPath.read(body, path + ".sample");
        return new Check(name, passed.get(0), failures.get(0).longValue(), sample.get(0));
    }

    private static List<String> failedNames(String body) {
        return JsonPath.read(body, "$.checks[?(@.passed == false)].name");
    }

    private String newAccount() {
        return accountService.create("Reconciliation Test").accountNumber();
    }

    private static String key() {
        return "rc-" + UUID.randomUUID();
    }
}

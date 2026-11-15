package com.bhuvan.ledger.service;

import com.bhuvan.ledger.dto.ReconciliationReport;
import com.bhuvan.ledger.dto.ReconciliationReport.Check;
import com.bhuvan.ledger.util.Money;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-derives the ledger from its entries and compares it with the stored balances and
 * idempotency records. This is what backs the claim that no money was created or lost.
 *
 * <p>Runs in one REPEATABLE READ transaction, so every check sees the same snapshot even while
 * postings keep committing; otherwise a transfer landing between two checks could make a
 * healthy ledger look broken.
 */
@Slf4j
@Service
public class ReconciliationService {

    static final int SAMPLE_LIMIT = 20;

    // Each query returns the offending identifier plus the total number of offenders
    // (count(*) over () is computed before LIMIT), so one round trip gives both.
    private static final String SNAPSHOT_MATCHES_ENTRIES = """
            select a.account_number, count(*) over ()
              from accounts a
              left join ledger_entries e on e.account_id = a.id
             group by a.id, a.account_number, a.balance
            having a.balance <> coalesce(sum(case when e.direction = 'CREDIT' then e.amount else -e.amount end), 0)
             order by a.id
             limit %d
            """.formatted(SAMPLE_LIMIT);

    // exactly one debit and one credit, each for the transaction's amount
    private static final String TRANSACTIONS_BALANCED = """
            select t.id::text, count(*) over ()
              from transactions t
              left join ledger_entries e on e.transaction_id = t.id
             group by t.id, t.amount
            having count(e.id) <> 2
                or coalesce(sum(e.amount) filter (where e.direction = 'DEBIT'), 0) <> t.amount
                or coalesce(sum(e.amount) filter (where e.direction = 'CREDIT'), 0) <> t.amount
             order by t.id
             limit %d
            """.formatted(SAMPLE_LIMIT);

    private static final String NO_NEGATIVE_USER_BALANCES = """
            select account_number, count(*) over ()
              from accounts
             where account_type = 'USER' and balance < 0
             order by id
             limit %d
            """.formatted(SAMPLE_LIMIT);

    // walks idx_entries_account_id_desc: one index probe per account
    private static final String LATEST_BALANCE_AFTER_MATCHES = """
            select a.account_number, count(*) over ()
              from accounts a
              join lateral (select e.balance_after from ledger_entries e
                             where e.account_id = a.id order by e.id desc limit 1) latest on true
             where latest.balance_after <> a.balance
             order by a.id
             limit %d
            """.formatted(SAMPLE_LIMIT);

    // a key that answered 201 must point at the transaction it created
    private static final String COMPLETED_KEYS_HAVE_TRANSACTIONS = """
            select k.idem_key, count(*) over ()
              from idempotency_keys k
             where k.status = 'COMPLETED' and k.http_status = 201
               and not exists (select 1 from transactions t
                                where t.id = k.transaction_id and t.idempotency_key = k.idem_key)
             order by k.idem_key
             limit %d
            """.formatted(SAMPLE_LIMIT);

    // and every transaction must have come through a completed key that points back at it
    private static final String TRANSACTIONS_HAVE_KEYS = """
            select t.id::text, count(*) over ()
              from transactions t
             where not exists (select 1 from idempotency_keys k
                                where k.idem_key = t.idempotency_key
                                  and k.status = 'COMPLETED' and k.transaction_id = t.id)
             order by t.id
             limit %d
            """.formatted(SAMPLE_LIMIT);

    private final JdbcTemplate jdbc;
    private final long statementTimeoutMs;

    public ReconciliationService(JdbcTemplate jdbc,
            @Value("${ledger.reconciliation.statement-timeout}") Duration statementTimeout) {
        this.jdbc = jdbc;
        this.statementTimeoutMs = statementTimeout.toMillis();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReconciliationReport run() {
        // Full scans of the ledger can outlast the 5s timeout every pooled connection gets.
        // SET LOCAL ends with this transaction, so the connection goes back to the pool unchanged.
        jdbc.execute("set local statement_timeout = " + statementTimeoutMs);

        long accounts = count("select count(*) from accounts");
        long transactions = count("select count(*) from transactions");
        long entries = count("select count(*) from ledger_entries");
        long totalBalance = count("select coalesce(sum(balance), 0) from accounts");

        List<Check> checks = List.of(
                check("SNAPSHOT_MATCHES_ENTRIES", SNAPSHOT_MATCHES_ENTRIES),
                check("TRANSACTIONS_BALANCED", TRANSACTIONS_BALANCED),
                globalZeroSum(totalBalance),
                check("NO_NEGATIVE_USER_BALANCES", NO_NEGATIVE_USER_BALANCES),
                check("LATEST_BALANCE_AFTER_MATCHES", LATEST_BALANCE_AFTER_MATCHES),
                check("COMPLETED_KEYS_HAVE_TRANSACTIONS", COMPLETED_KEYS_HAVE_TRANSACTIONS),
                check("TRANSACTIONS_HAVE_KEYS", TRANSACTIONS_HAVE_KEYS));

        boolean healthy = checks.stream().allMatch(Check::passed);
        ReconciliationReport report = new ReconciliationReport(healthy, Instant.now().truncatedTo(ChronoUnit.MILLIS),
                accounts, transactions, entries, Money.format(totalBalance), checks);
        if (healthy) {
            log.info("Reconciliation healthy: accounts={} transactions={} entries={}",
                    accounts, transactions, entries);
        } else {
            log.error("Reconciliation FAILED: {}", report.failedChecks());
        }
        return report;
    }

    private static Check globalZeroSum(long totalBalance) {
        boolean passed = totalBalance == 0;
        return new Check("GLOBAL_ZERO_SUM", passed, passed ? 0 : 1,
                passed ? List.of() : List.of(Money.format(totalBalance)));
    }

    private Check check(String name, String sql) {
        List<String> sample = new ArrayList<>();
        long[] failures = {0};
        jdbc.query(sql, rs -> {
            sample.add(rs.getString(1));
            failures[0] = rs.getLong(2);
        });
        return new Check(name, failures[0] == 0, failures[0], List.copyOf(sample));
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }
}

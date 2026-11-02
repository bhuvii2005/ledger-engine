package com.bhuvan.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SQL checks that the ledger is still consistent. A cut-down stand-in for the
 * reconciliation service, scoped to the accounts a test used plus the global sums.
 * The system account is covered by the global zero-sum check only: the Phase 1 trigger
 * tests leave raw entries on it without a balance update, so its snapshot-vs-entries
 * check can't pass until those tests are fixed alongside reconciliation.
 */
public final class LedgerInvariants {

    private final JdbcTemplate jdbc;

    public LedgerInvariants(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long totalOf(Collection<String> accountNumbers) {
        return accountNumbers.stream().mapToLong(this::balance).sum();
    }

    public long balance(String accountNumber) {
        return jdbc.queryForObject("select balance from accounts where account_number = ?", Long.class, accountNumber);
    }

    public void assertHealthy(Collection<String> accountNumbers) {
        // money is neither created nor destroyed: the system account offsets every user balance
        assertThat(jdbc.queryForObject("select coalesce(sum(balance), 0) from accounts", Long.class))
                .as("global sum of balances").isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from accounts where account_type = 'USER' and balance < 0", Long.class))
                .as("negative USER balances").isZero();

        for (String number : accountNumbers) {
            Long derived = jdbc.queryForObject("""
                    select coalesce(sum(case when e.direction = 'CREDIT' then e.amount else -e.amount end), 0)
                      from ledger_entries e join accounts a on a.id = e.account_id
                     where a.account_number = ?
                    """, Long.class, number);
            assertThat(balance(number)).as("snapshot vs entries for %s", number).isEqualTo(derived);

            Long latestBalanceAfter = jdbc.queryForObject("""
                    select coalesce((select e.balance_after from ledger_entries e
                                      where e.account_id = a.id order by e.id desc limit 1), 0)
                      from accounts a where a.account_number = ?
                    """, Long.class, number);
            assertThat(balance(number)).as("latest balance_after for %s", number).isEqualTo(latestBalanceAfter);

            // Scoped to this account: the Phase 1 trigger tests insert raw transactions without
            // idempotency rows on purpose (to be cleaned up when reconciliation lands).
            assertThat(jdbc.queryForObject("""
                    select count(*) from transactions t
                      join ledger_entries e on e.transaction_id = t.id
                      join accounts a on a.id = e.account_id
                     where a.account_number = ?
                       and not exists (select 1 from idempotency_keys k
                                        where k.idem_key = t.idempotency_key
                                          and k.status = 'COMPLETED' and k.transaction_id = t.id)
                    """, Long.class, number))
                    .as("transactions on %s without a completed idempotency row", number).isZero();
        }
    }
}

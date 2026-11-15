package com.bhuvan.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.bhuvan.ledger.dto.ReconciliationReport;
import com.bhuvan.ledger.service.ReconciliationService;
import java.util.Collection;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Balance lookups for tests, and the end-of-test health check: a full reconciliation run
 * over every account (the system account included), not just the ones the test touched.
 */
public final class LedgerInvariants {

    private final JdbcTemplate jdbc;
    private final ReconciliationService reconciliation;

    public LedgerInvariants(JdbcTemplate jdbc, ReconciliationService reconciliation) {
        this.jdbc = jdbc;
        this.reconciliation = reconciliation;
    }

    public long totalOf(Collection<String> accountNumbers) {
        return accountNumbers.stream().mapToLong(this::balance).sum();
    }

    public long balance(String accountNumber) {
        return jdbc.queryForObject("select balance from accounts where account_number = ?", Long.class, accountNumber);
    }

    public ReconciliationReport assertHealthy() {
        ReconciliationReport report = reconciliation.run();
        assertThat(report.failedChecks()).as("failed reconciliation checks").isEmpty();
        assertThat(report.healthy()).isTrue();
        return report;
    }
}

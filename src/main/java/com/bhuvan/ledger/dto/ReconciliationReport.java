package com.bhuvan.ledger.dto;

import java.time.Instant;
import java.util.List;

/**
 * Result of one reconciliation run. {@code totalBalance} is the sum of every account balance,
 * which must be exactly 0.00: the system account's negative balance offsets all user money.
 */
public record ReconciliationReport(
        boolean healthy,
        Instant checkedAt,
        long accounts,
        long transactions,
        long entries,
        String totalBalance,
        List<Check> checks) {

    /** One check: how many rows broke it, and up to 20 of their identifiers. */
    public record Check(String name, boolean passed, long failures, List<String> sample) {
    }

    public List<Check> failedChecks() {
        return checks.stream().filter(c -> !c.passed()).toList();
    }
}

package com.bhuvan.ledger.service;

import com.bhuvan.ledger.dto.ReconciliationReport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs reconciliation in the background and makes a failure visible: an ERROR log line and
 * a {@code ledger.reconciliation.failures} increment that an alert can watch. Off in tests.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ledger.reconciliation.scheduled-enabled", havingValue = "true")
public class ReconciliationJob {

    private final ReconciliationService reconciliationService;
    private final Counter failures;

    public ReconciliationJob(ReconciliationService reconciliationService, MeterRegistry meterRegistry) {
        this.reconciliationService = reconciliationService;
        this.failures = meterRegistry.counter("ledger.reconciliation.failures");
    }

    @Scheduled(initialDelayString = "${ledger.reconciliation.interval-ms}",
            fixedDelayString = "${ledger.reconciliation.interval-ms}")
    public void run() {
        // gives every line of this run one id to grep for, like a request gets
        MDC.put("correlationId", "reconciliation-" + UUID.randomUUID());
        try {
            ReconciliationReport report = reconciliationService.run();
            if (!report.healthy()) {
                // the service has already logged which checks failed
                failures.increment();
            }
        } catch (RuntimeException e) {
            // a run that couldn't finish has verified nothing, so it counts as a failure too
            failures.increment();
            log.error("Reconciliation could not run", e);
        } finally {
            MDC.remove("correlationId");
        }
    }
}

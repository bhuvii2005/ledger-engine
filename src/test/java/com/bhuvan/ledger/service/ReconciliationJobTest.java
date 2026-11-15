package com.bhuvan.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bhuvan.ledger.config.SchedulingConfig;
import com.bhuvan.ledger.dto.ReconciliationReport;
import com.bhuvan.ledger.dto.ReconciliationReport.Check;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.QueryTimeoutException;

class ReconciliationJobTest {

    private final ReconciliationService service = mock(ReconciliationService.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ReconciliationJob job = new ReconciliationJob(service, registry);

    @Test
    void unhealthyRunIncrementsTheFailureCounter() {
        when(service.run()).thenReturn(report(false));
        job.run();
        assertThat(failures(registry)).isEqualTo(1.0);
    }

    @Test
    void healthyRunLeavesTheCounterAlone() {
        when(service.run()).thenReturn(report(true));
        job.run();
        assertThat(failures(registry)).isZero();
    }

    @Test
    void runThatCannotFinishCountsAsAFailureAndDoesNotKillTheSchedule() {
        when(service.run()).thenThrow(new QueryTimeoutException("statement timeout"));
        job.run(); // must not throw: an exception here would only end up in the scheduler's log
        assertThat(failures(registry)).isEqualTo(1.0);
    }

    @Test
    void eachRunHasItsOwnCorrelationIdForItsLogLines() {
        AtomicReference<String> seen = new AtomicReference<>();
        when(service.run()).thenAnswer(inv -> {
            seen.set(MDC.get("correlationId"));
            return report(true);
        });
        job.run();
        assertThat(seen.get()).startsWith("reconciliation-");
        assertThat(MDC.get("correlationId")).as("cleared after the run").isNull();
    }

    @Test
    void whenEnabledItRunsOnTheConfiguredInterval() {
        when(service.run()).thenReturn(report(false));
        contextRunner()
                .withPropertyValues("ledger.reconciliation.scheduled-enabled=true",
                        "ledger.reconciliation.interval-ms=50")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(ReconciliationJob.class);
                    MeterRegistry meters = ctx.getBean(MeterRegistry.class);
                    await().atMost(Duration.ofSeconds(5)).until(() -> failures(meters) >= 2);
                });
    }

    @Test
    void whenDisabledThereIsNoJobAndNoScheduling() {
        contextRunner()
                .withPropertyValues("ledger.reconciliation.scheduled-enabled=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(ReconciliationJob.class);
                    assertThat(ctx).doesNotHaveBean(SchedulingConfig.class);
                });
    }

    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withBean(ReconciliationService.class, () -> service)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withUserConfiguration(SchedulingConfig.class, ReconciliationJob.class);
    }

    private static double failures(MeterRegistry meters) {
        return meters.counter("ledger.reconciliation.failures").count();
    }

    private static ReconciliationReport report(boolean healthy) {
        return new ReconciliationReport(healthy, Instant.now(), 1, 0, 0, healthy ? "0.00" : "0.01",
                List.of(new Check("GLOBAL_ZERO_SUM", healthy, healthy ? 0 : 1,
                        healthy ? List.of() : List.of("0.01"))));
    }
}

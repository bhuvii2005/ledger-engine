package com.bhuvan.ledger.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// The reconciliation job is the only scheduled work, so scheduling is switched on with it.
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "ledger.reconciliation.scheduled-enabled", havingValue = "true")
public class SchedulingConfig {
}

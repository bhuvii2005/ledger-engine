package com.bhuvan.ledger.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhuvan.ledger.AbstractIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Runs after every other test class (see junit-platform.properties) against the same Postgres,
 * which by then holds everything the suite posted: concurrency storms, reversals, Redis outages
 * and the raw-SQL trigger tests. The whole ledger must still reconcile.
 */
@Slf4j
@Order(Integer.MAX_VALUE)
class FullSuiteReconciliationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void ledgerReconcilesAfterTheWholeSuite() throws Exception {
        mockMvc.perform(get("/api/v1/admin/reconciliation"))
                .andDo(result -> log.info("RESULT full-suite reconciliation: {}",
                        result.getResponse().getContentAsString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthy").value(true))
                .andExpect(jsonPath("$.totalBalance").value("0.00"));
    }
}

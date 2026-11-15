package com.bhuvan.ledger.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.bhuvan.ledger.AbstractIntegrationTest;
import com.bhuvan.ledger.seed.SeedRunner;
import com.bhuvan.ledger.service.AccountService;
import com.bhuvan.ledger.service.ReconciliationService;
import com.bhuvan.ledger.service.TransactionFacade;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

class SeedRunnerTest extends AbstractIntegrationTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private TransactionFacade facade;

    @Autowired
    private ReconciliationService reconciliation;

    @Autowired
    private ConfigurableApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void seedIsNotActiveOutsideTheSeedProfile() {
        assertThat(context.getBeanNamesForType(SeedRunner.class)).isEmpty();
    }

    @Test
    void seedFundsEachAccountThroughRealPostingsAndWritesTheCsv(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("loadtest/accounts.csv");
        SeedRunner seed = new SeedRunner(accountService, facade, reconciliation, context, "SYS-CASH-0001", 3,
                10_000_000, csv);

        List<String> accounts = seed.seed();

        assertThat(accounts).hasSize(3).doesNotHaveDuplicates();
        assertThat(Files.readAllLines(csv)).containsExactlyElementsOf(accounts);
        for (String acc : accounts) {
            assertThat(jdbc.queryForObject("select balance from accounts where account_number = ?", Long.class,
                    acc)).isEqualTo(10_000_000L);
            // posted through the facade: a completed idempotency row points at the deposit
            assertThat(jdbc.queryForObject("""
                    select count(*) from idempotency_keys k join transactions t on t.id = k.transaction_id
                     where k.idem_key = ? and k.status = 'COMPLETED' and t.txn_type = 'DEPOSIT'
                    """, Integer.class, "seed-" + acc)).isEqualTo(1);
        }
        assertThat(reconciliation.run().healthy()).isTrue();
    }
}

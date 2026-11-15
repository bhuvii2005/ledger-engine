package com.bhuvan.ledger.seed;

import com.bhuvan.ledger.dto.ReconciliationReport;
import com.bhuvan.ledger.entity.TransactionType;
import com.bhuvan.ledger.service.AccountService;
import com.bhuvan.ledger.service.PostingCommand;
import com.bhuvan.ledger.service.PostingResult;
import com.bhuvan.ledger.service.ReconciliationService;
import com.bhuvan.ledger.service.TransactionFacade;
import com.bhuvan.ledger.util.Money;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Load-test data, created through the same services the API uses, so the seeded ledger
 * reconciles like any other: every deposit is a real posting with its own idempotency key.
 * Writes the account numbers to a CSV for JMeter, then shuts the application down.
 */
@Slf4j
@Component
@Profile("seed")
public class SeedRunner implements CommandLineRunner {

    private final AccountService accountService;
    private final TransactionFacade facade;
    private final ReconciliationService reconciliation;
    private final ConfigurableApplicationContext context;
    private final String systemAccount;
    private final int accounts;
    private final long depositPaise;
    private final Path output;

    public SeedRunner(AccountService accountService, TransactionFacade facade,
            ReconciliationService reconciliation, ConfigurableApplicationContext context,
            @Value("${ledger.system-account-number}") String systemAccount,
            @Value("${ledger.seed.accounts}") int accounts,
            @Value("${ledger.seed.deposit-paise}") long depositPaise,
            @Value("${ledger.seed.output}") Path output) {
        this.accountService = accountService;
        this.facade = facade;
        this.reconciliation = reconciliation;
        this.context = context;
        this.systemAccount = systemAccount;
        this.accounts = accounts;
        this.depositPaise = depositPaise;
        this.output = output;
    }

    @Override
    public void run(String... args) {
        seed();
        // a one-shot job: stop here instead of staying up as a server
        System.exit(SpringApplication.exit(context, () -> 0));
    }

    /** Creates and funds the accounts, writes the CSV, and fails unless the ledger reconciles. */
    public List<String> seed() {
        long started = System.nanoTime();
        List<String> numbers = new ArrayList<>(accounts);
        for (int i = 0; i < accounts; i++) {
            String number = accountService.create("Load Test " + (i + 1)).accountNumber();
            PostingResult deposit = facade.execute(PostingCommand.of(TransactionType.DEPOSIT, "seed-" + number,
                    systemAccount, number, depositPaise, "INR", "load test seed", null));
            if (deposit.httpStatus() != 201) {
                throw new IllegalStateException("Seed deposit into " + number + " failed: " + deposit.body());
            }
            numbers.add(number);
        }
        writeCsv(numbers);
        long ms = (System.nanoTime() - started) / 1_000_000;
        log.info("Seeded {} accounts with {} each in {} ms; wrote {}", accounts, Money.format(depositPaise), ms,
                output.toAbsolutePath());

        ReconciliationReport report = reconciliation.run();
        if (!report.healthy()) {
            throw new IllegalStateException("Ledger does not reconcile after seeding: " + report.failedChecks());
        }
        return numbers;
    }

    private void writeCsv(List<String> numbers) {
        try {
            if (output.getParent() != null) {
                Files.createDirectories(output.getParent());
            }
            // one account number per line, no header: JMeter reads the list as-is
            Files.write(output, numbers);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + output, e);
        }
    }
}

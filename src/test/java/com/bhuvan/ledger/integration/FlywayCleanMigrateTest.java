package com.bhuvan.ledger.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bhuvan.ledger.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class FlywayCleanMigrateTest extends AbstractIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void cleanThenMigrateRecreatesSeedDataAndTriggers() throws SQLException {
        // Clean drops everything, so it runs on a scratch database in the same container. On the
        // shared one it would wipe what earlier test classes posted, and the end-of-suite
        // reconciliation would only see the classes that ran after this one.
        String scratch = "flyway_clean_" + System.nanoTime();
        String sharedUrl;
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            sharedUrl = c.getMetaData().getURL();
            st.execute("create database " + scratch);
        }
        try {
            String scratchUrl = sharedUrl.replaceFirst("/ledger(\\?|$)", "/" + scratch + "$1");
            DriverManagerDataSource scratchDb = new DriverManagerDataSource(scratchUrl, "ledger", "ledger");

            // A fresh Flyway instance with clean explicitly enabled, instead of
            // the app's own Flyway bean, which runs with clean-disabled=true.
            Flyway flyway = Flyway.configure()
                    .dataSource(scratchDb)
                    .cleanDisabled(false)
                    .load();
            flyway.migrate();
            flyway.clean();
            flyway.migrate();

            try (Connection c = scratchDb.getConnection()) {
                try (Statement st = c.createStatement()) {
                    var rs = st.executeQuery(
                            "select count(*) from accounts where account_number = 'SYS-CASH-0001'");
                    rs.next();
                    assertThat(rs.getLong(1)).isEqualTo(1);
                }

                c.setAutoCommit(false);
                try (Statement st = c.createStatement()) {
                    assertThatThrownBy(() -> st.execute("truncate ledger_entries"))
                            .isInstanceOf(SQLException.class)
                            .hasMessageContaining("append-only");
                }
                c.rollback();
            }
        } finally {
            try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
                st.execute("drop database if exists " + scratch + " with (force)");
            }
        }
    }
}

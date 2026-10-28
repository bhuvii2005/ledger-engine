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

class FlywayCleanMigrateTest extends AbstractIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void cleanThenMigrateRecreatesSeedDataAndTriggers() throws SQLException {
        // A fresh Flyway instance with clean explicitly enabled, instead of
        // the app's own Flyway bean, which runs with clean-disabled=true.
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .cleanDisabled(false)
                .load();

        flyway.clean();
        flyway.migrate();

        try (Connection c = dataSource.getConnection()) {
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
        }
    }
}

package com.bhuvan.ledger.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.bhuvan.ledger.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DatasourceConfigTest extends AbstractIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void connectionInitSqlSetsLockTimeout() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            var rs = st.executeQuery("show lock_timeout");
            rs.next();
            assertThat(rs.getString(1)).isEqualTo("2s");
        }
    }

    @Test
    void connectionInitSqlSetsStatementTimeout() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            var rs = st.executeQuery("show statement_timeout");
            rs.next();
            assertThat(rs.getString(1)).isEqualTo("5s");
        }
    }
}

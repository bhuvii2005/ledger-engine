package com.bhuvan.ledger.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bhuvan.ledger.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AppendOnlyTriggerTest extends AbstractIntegrationTest {

    @Autowired
    private DataSource dataSource;

    private long systemAccountId;
    private long userAccountId;

    @BeforeEach
    void seedAccounts() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            systemAccountId = queryId(c, "select id from accounts where account_number = 'SYS-CASH-0001'");

            String accountNumber = "ACC-T" + (System.nanoTime() % 1_000_000_000_000_000L);
            try (PreparedStatement ps = c.prepareStatement(
                    "insert into accounts (account_number, owner_name, account_type) values (?, 'Test User', 'USER')")) {
                ps.setString(1, accountNumber);
                ps.executeUpdate();
            }
            userAccountId = queryId(c, "select id from accounts where account_number = '" + accountNumber + "'");
        }
    }

    private long queryId(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            var rs = st.executeQuery(sql);
            rs.next();
            return rs.getLong(1);
        }
    }

    private UUID insertBalancedTransaction(Connection c) throws SQLException {
        UUID txnId = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
                "insert into transactions (id, txn_type, amount, idempotency_key) values (?, 'DEPOSIT', 10000, ?)")) {
            ps.setObject(1, txnId);
            ps.setString(2, "key-" + txnId);
            ps.executeUpdate();
        }
        insertEntry(c, txnId, systemAccountId, "DEBIT", -10000);
        insertEntry(c, txnId, userAccountId, "CREDIT", 10000);
        return txnId;
    }

    private void insertEntry(Connection c, UUID txnId, long accountId, String direction, long balanceAfter)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "insert into ledger_entries (transaction_id, account_id, direction, amount, balance_after) "
                        + "values (?, ?, ?, 10000, ?)")) {
            ps.setObject(1, txnId);
            ps.setLong(2, accountId);
            ps.setString(3, direction);
            ps.setLong(4, balanceAfter);
            ps.executeUpdate();
        }
    }

    @Test
    void updateOnLedgerEntriesIsRejected() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            UUID txnId = insertBalancedTransaction(c);
            c.commit();

            try (PreparedStatement ps = c.prepareStatement(
                    "update ledger_entries set amount = amount + 1 where transaction_id = ?")) {
                ps.setObject(1, txnId);
                assertThatThrownBy(ps::executeUpdate)
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("append-only");
            }
        }
    }

    @Test
    void deleteOnTransactionsIsRejected() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            UUID txnId = insertBalancedTransaction(c);
            c.commit();

            try (PreparedStatement ps = c.prepareStatement("delete from transactions where id = ?")) {
                ps.setObject(1, txnId);
                assertThatThrownBy(ps::executeUpdate)
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("append-only");
            }
        }
    }

    @Test
    void deleteOnLedgerEntriesIsRejected() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            UUID txnId = insertBalancedTransaction(c);
            c.commit();

            try (PreparedStatement ps = c.prepareStatement(
                    "delete from ledger_entries where transaction_id = ?")) {
                ps.setObject(1, txnId);
                assertThatThrownBy(ps::executeUpdate)
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("append-only");
            }
        }
    }

    @Test
    void updateOnTransactionsIsRejected() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            UUID txnId = insertBalancedTransaction(c);
            c.commit();

            try (PreparedStatement ps = c.prepareStatement(
                    "update transactions set description = 'edited' where id = ?")) {
                ps.setObject(1, txnId);
                assertThatThrownBy(ps::executeUpdate)
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("append-only");
            }
        }
    }

    @Test
    void truncateOnLedgerEntriesIsRejected() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            insertBalancedTransaction(c);
            c.commit();

            try (Statement st = c.createStatement()) {
                assertThatThrownBy(() -> st.execute("truncate ledger_entries"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("append-only");
            }
        }
    }

    @Test
    void truncateOnTransactionsIsRejected() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            insertBalancedTransaction(c);
            c.commit();

            // plain TRUNCATE on transactions alone would hit the FK from
            // ledger_entries first; CASCADE expands it to both tables so the
            // append-only trigger (not the FK guard) is what actually fires.
            try (Statement st = c.createStatement()) {
                assertThatThrownBy(() -> st.execute("truncate transactions cascade"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("append-only");
            }
        }
    }

    @Test
    void oneLeggedTransactionFailsAtCommit() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            UUID txnId = UUID.randomUUID();
            try (PreparedStatement ps = c.prepareStatement(
                    "insert into transactions (id, txn_type, amount, idempotency_key) values (?, 'DEPOSIT', 10000, ?)")) {
                ps.setObject(1, txnId);
                ps.setString(2, "key-" + txnId);
                ps.executeUpdate();
            }
            insertEntry(c, txnId, systemAccountId, "DEBIT", -10000);
            // deliberately no matching CREDIT entry

            assertThatThrownBy(c::commit)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("unbalanced");
        }
    }

    @Test
    void transactionWithNoEntriesFailsAtCommit() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            UUID txnId = UUID.randomUUID();
            try (PreparedStatement ps = c.prepareStatement(
                    "insert into transactions (id, txn_type, amount, idempotency_key) values (?, 'DEPOSIT', 10000, ?)")) {
                ps.setObject(1, txnId);
                ps.setString(2, "key-" + txnId);
                ps.executeUpdate();
            }
            // no entries inserted at all

            assertThatThrownBy(c::commit)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("unbalanced");
        }
    }

    @Test
    void balancedTransactionCommitsCleanly() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            UUID txnId = insertBalancedTransaction(c);
            c.commit();

            long count = queryId(c, "select count(*) from ledger_entries where transaction_id = '" + txnId + "'");
            assertThat(count).isEqualTo(2);
        }
    }
}

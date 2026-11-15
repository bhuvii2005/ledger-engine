package com.bhuvan.ledger.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bhuvan.ledger.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves the database itself refuses to edit history or commit an unbalanced transaction,
 * using raw JDBC so no application code is involved.
 *
 * <p>The ledger tables are append-only, so whatever these tests commit stays for the rest of
 * the run and has to pass reconciliation. The rejection tests therefore insert their rows and
 * try to change them inside one transaction that is always rolled back (row triggers fire on
 * uncommitted rows too). The one test that must commit writes a complete posting, exactly as
 * PostingService would: locked balances, correct balance_after and an idempotency row.
 */
class AppendOnlyTriggerTest extends AbstractIntegrationTest {

    private static final long AMOUNT = 10_000;

    @Autowired
    private DataSource dataSource;

    private long systemAccountId;
    private long userAccountId;

    @BeforeEach
    void seedAccounts() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            systemAccountId = queryLong(c, "select id from accounts where account_number = 'SYS-CASH-0001'");

            // a brand-new USER account with no entries and a zero balance is already consistent
            String accountNumber = "ACC-T" + (System.nanoTime() % 1_000_000_000_000_000L);
            try (PreparedStatement ps = c.prepareStatement(
                    "insert into accounts (account_number, owner_name, account_type) values (?, 'Test User', 'USER')")) {
                ps.setString(1, accountNumber);
                ps.executeUpdate();
            }
            userAccountId = queryLong(c, "select id from accounts where account_number = '" + accountNumber + "'");
        }
    }

    @Test
    void updateOnLedgerEntriesIsRejected() throws SQLException {
        inRolledBackTransaction(c -> {
            UUID txnId = insertBalancedTransaction(c);
            try (PreparedStatement ps = c.prepareStatement(
                    "update ledger_entries set amount = amount + 1 where transaction_id = ?")) {
                ps.setObject(1, txnId);
                assertThatThrownBy(ps::executeUpdate)
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("append-only");
            }
        });
    }

    @Test
    void deleteOnTransactionsIsRejected() throws SQLException {
        inRolledBackTransaction(c -> {
            UUID txnId = insertBalancedTransaction(c);
            try (PreparedStatement ps = c.prepareStatement("delete from transactions where id = ?")) {
                ps.setObject(1, txnId);
                assertThatThrownBy(ps::executeUpdate)
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("append-only");
            }
        });
    }

    @Test
    void deleteOnLedgerEntriesIsRejected() throws SQLException {
        inRolledBackTransaction(c -> {
            UUID txnId = insertBalancedTransaction(c);
            try (PreparedStatement ps = c.prepareStatement(
                    "delete from ledger_entries where transaction_id = ?")) {
                ps.setObject(1, txnId);
                assertThatThrownBy(ps::executeUpdate)
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("append-only");
            }
        });
    }

    @Test
    void updateOnTransactionsIsRejected() throws SQLException {
        inRolledBackTransaction(c -> {
            UUID txnId = insertBalancedTransaction(c);
            try (PreparedStatement ps = c.prepareStatement(
                    "update transactions set description = 'edited' where id = ?")) {
                ps.setObject(1, txnId);
                assertThatThrownBy(ps::executeUpdate)
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("append-only");
            }
        });
    }

    @Test
    void truncateOnLedgerEntriesIsRejected() throws SQLException {
        // TRUNCATE triggers are per statement, so no rows are needed. Inserting some first would
        // fail differently: Postgres refuses to truncate a table with pending deferred triggers.
        inRolledBackTransaction(c -> {
            try (Statement st = c.createStatement()) {
                assertThatThrownBy(() -> st.execute("truncate ledger_entries"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("append-only");
            }
        });
    }

    @Test
    void truncateOnTransactionsIsRejected() throws SQLException {
        inRolledBackTransaction(c -> {
            // plain TRUNCATE on transactions alone would hit the FK from
            // ledger_entries first; CASCADE expands it to both tables so the
            // append-only trigger (not the FK guard) is what actually fires.
            try (Statement st = c.createStatement()) {
                assertThatThrownBy(() -> st.execute("truncate transactions cascade"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("append-only");
            }
        });
    }

    @Test
    void oneLeggedTransactionFailsAtCommit() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            UUID txnId = insertTransactionRow(c);
            insertEntry(c, txnId, systemAccountId, "DEBIT", -AMOUNT);
            // deliberately no matching CREDIT entry

            assertThatThrownBy(c::commit)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("unbalanced");
            assertThat(countRows(c, txnId)).as("nothing of the failed commit is left").isZero();
        }
    }

    @Test
    void transactionWithNoEntriesFailsAtCommit() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            UUID txnId = insertTransactionRow(c);
            // no entries inserted at all

            assertThatThrownBy(c::commit)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("unbalanced");
            assertThat(countRows(c, txnId)).as("nothing of the failed commit is left").isZero();
        }
    }

    @Test
    void balancedTransactionCommitsCleanly() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            UUID txnId = insertCompletePosting(c);
            c.commit();

            assertThat(queryLong(c, "select count(*) from ledger_entries where transaction_id = '" + txnId + "'"))
                    .isEqualTo(2);
            assertThat(queryLong(c, "select balance from accounts where id = " + userAccountId)).isEqualTo(AMOUNT);
            c.commit();
        }
    }

    // ---------- helpers ----------

    private interface SqlWork {
        void run(Connection c) throws SQLException;
    }

    private void inRolledBackTransaction(SqlWork work) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                work.run(c);
            } finally {
                c.rollback();
            }
        }
    }

    /** Balanced, but balances and idempotency are ignored: only for transactions that get rolled back. */
    private UUID insertBalancedTransaction(Connection c) throws SQLException {
        UUID txnId = insertTransactionRow(c);
        insertEntry(c, txnId, systemAccountId, "DEBIT", -AMOUNT);
        insertEntry(c, txnId, userAccountId, "CREDIT", AMOUNT);
        return txnId;
    }

    /** A deposit written the way PostingService writes one, so it leaves the ledger consistent. */
    private UUID insertCompletePosting(Connection c) throws SQLException {
        // lock in id order, like the app, so this can't deadlock with a posting running alongside
        long systemBalance = lockBalance(c, systemAccountId);
        long userBalance = lockBalance(c, userAccountId);
        long systemAfter = systemBalance - AMOUNT;
        long userAfter = userBalance + AMOUNT;

        UUID txnId = insertTransactionRow(c);
        insertEntry(c, txnId, systemAccountId, "DEBIT", systemAfter);
        insertEntry(c, txnId, userAccountId, "CREDIT", userAfter);
        setBalance(c, systemAccountId, systemAfter);
        setBalance(c, userAccountId, userAfter);
        try (PreparedStatement ps = c.prepareStatement("""
                insert into idempotency_keys (idem_key, request_hash, operation, status, http_status, transaction_id)
                values (?, ?, 'DEPOSIT', 'COMPLETED', 201, ?)
                """)) {
            ps.setString(1, "key-" + txnId);
            ps.setString(2, "0".repeat(64));
            ps.setObject(3, txnId);
            ps.executeUpdate();
        }
        return txnId;
    }

    private UUID insertTransactionRow(Connection c) throws SQLException {
        UUID txnId = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
                "insert into transactions (id, txn_type, amount, idempotency_key) values (?, 'DEPOSIT', ?, ?)")) {
            ps.setObject(1, txnId);
            ps.setLong(2, AMOUNT);
            ps.setString(3, "key-" + txnId);
            ps.executeUpdate();
        }
        return txnId;
    }

    private void insertEntry(Connection c, UUID txnId, long accountId, String direction, long balanceAfter)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "insert into ledger_entries (transaction_id, account_id, direction, amount, balance_after) "
                        + "values (?, ?, ?, ?, ?)")) {
            ps.setObject(1, txnId);
            ps.setLong(2, accountId);
            ps.setString(3, direction);
            ps.setLong(4, AMOUNT);
            ps.setLong(5, balanceAfter);
            ps.executeUpdate();
        }
    }

    private long lockBalance(Connection c, long accountId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "select balance from accounts where id = ? for no key update")) {
            ps.setLong(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void setBalance(Connection c, long accountId, long balance) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "update accounts set balance = ?, updated_at = now() where id = ?")) {
            ps.setLong(1, balance);
            ps.setLong(2, accountId);
            ps.executeUpdate();
        }
    }

    private long countRows(Connection c, UUID txnId) throws SQLException {
        return queryLong(c, "select (select count(*) from transactions where id = '" + txnId + "')"
                + " + (select count(*) from ledger_entries where transaction_id = '" + txnId + "')");
    }

    private long queryLong(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}

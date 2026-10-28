-- 1. Append-only tables
CREATE OR REPLACE FUNCTION reject_modification() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Table % is append-only: % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'P0001';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_entries_no_update_delete
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION reject_modification();

CREATE TRIGGER trg_ledger_entries_no_truncate
    BEFORE TRUNCATE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

CREATE TRIGGER trg_transactions_no_update_delete
    BEFORE UPDATE OR DELETE ON transactions
    FOR EACH ROW EXECUTE FUNCTION reject_modification();

CREATE TRIGGER trg_transactions_no_truncate
    BEFORE TRUNCATE ON transactions
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

-- 2. Every transaction must be balanced at COMMIT time
CREATE OR REPLACE FUNCTION check_transaction_balanced() RETURNS trigger AS $$
DECLARE
    v_txn_id UUID;
    v_debit  BIGINT;
    v_credit BIGINT;
BEGIN
    IF TG_TABLE_NAME = 'transactions' THEN
        v_txn_id := NEW.id;
    ELSE
        v_txn_id := NEW.transaction_id;
    END IF;

    SELECT COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0),
           COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0)
      INTO v_debit, v_credit
      FROM ledger_entries
     WHERE transaction_id = v_txn_id;

    IF v_debit = 0 OR v_debit <> v_credit THEN
        RAISE EXCEPTION 'Transaction % is unbalanced (debit=%, credit=%)',
            v_txn_id, v_debit, v_credit USING ERRCODE = 'P0002';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_entries_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_transaction_balanced();

-- Catches a transaction row inserted with zero entries
CREATE CONSTRAINT TRIGGER trg_transaction_has_entries
    AFTER INSERT ON transactions
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_transaction_balanced();

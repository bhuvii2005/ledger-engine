CREATE SEQUENCE account_number_seq START 1000000001;

CREATE TABLE accounts (
    id              BIGSERIAL PRIMARY KEY,
    account_number  VARCHAR(20)  NOT NULL UNIQUE,
    owner_name      VARCHAR(100) NOT NULL,
    account_type    VARCHAR(10)  NOT NULL CHECK (account_type IN ('USER', 'SYSTEM')),
    currency        CHAR(3)      NOT NULL DEFAULT 'INR' CHECK (currency = 'INR'),
    balance         BIGINT       NOT NULL DEFAULT 0,
    status          VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_user_balance_non_negative
        CHECK (account_type = 'SYSTEM' OR balance >= 0)
);

CREATE TABLE transactions (
    id               UUID         PRIMARY KEY,
    txn_type         VARCHAR(12)  NOT NULL
                     CHECK (txn_type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'REVERSAL')),
    amount           BIGINT       NOT NULL CHECK (amount > 0),
    currency         CHAR(3)      NOT NULL DEFAULT 'INR' CHECK (currency = 'INR'),
    description      VARCHAR(255),
    idempotency_key  VARCHAR(64)  NOT NULL UNIQUE,
    reverses_txn_id  UUID         UNIQUE REFERENCES transactions(id),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_reversal_link CHECK (
        (txn_type = 'REVERSAL' AND reverses_txn_id IS NOT NULL) OR
        (txn_type <> 'REVERSAL' AND reverses_txn_id IS NULL)
    )
);

CREATE TABLE ledger_entries (
    id              BIGSERIAL    PRIMARY KEY,
    transaction_id  UUID         NOT NULL REFERENCES transactions(id),
    account_id      BIGINT       NOT NULL REFERENCES accounts(id),
    direction       VARCHAR(6)   NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount          BIGINT       NOT NULL CHECK (amount > 0),
    balance_after   BIGINT       NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_entry_per_txn_direction UNIQUE (transaction_id, direction)
);

CREATE INDEX idx_entries_account_id_desc ON ledger_entries (account_id, id DESC);
CREATE INDEX idx_entries_transaction     ON ledger_entries (transaction_id);

CREATE TABLE idempotency_keys (
    idem_key        VARCHAR(64)  PRIMARY KEY,
    request_hash    CHAR(64)     NOT NULL,
    operation       VARCHAR(20)  NOT NULL,
    status          VARCHAR(12)  NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    http_status     INT,
    response_body   TEXT,
    transaction_id  UUID,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

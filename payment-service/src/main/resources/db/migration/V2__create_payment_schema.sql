-- One payment per order (ADR-0009). Card tokens only: card numbers never reach this service.
CREATE TABLE payments (
    id                  uuid           PRIMARY KEY,
    order_id            uuid           NOT NULL,
    customer_id         uuid           NOT NULL,
    amount              numeric(12, 2) NOT NULL CHECK (amount > 0),
    currency            varchar(3)     NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    card_token          varchar(64)    NOT NULL,
    status              varchar(16)    NOT NULL CHECK (status IN ('AUTHORIZING', 'AUTHORIZED', 'DECLINED',
                            'CAPTURING', 'CAPTURED', 'CAPTURE_FAILED', 'VOIDING', 'VOIDED')),
    provider_reference  varchar(64),
    void_requested      boolean        NOT NULL DEFAULT false,
    failure_reason      varchar(500),
    created_at          timestamptz    NOT NULL,
    updated_at          timestamptz    NOT NULL,
    CONSTRAINT payments_order_unique UNIQUE (order_id)
);

-- The resolver looks for provider calls whose outcome is still unknown, oldest first.
CREATE INDEX payments_in_flight ON payments (updated_at)
    WHERE status IN ('AUTHORIZING', 'CAPTURING', 'VOIDING');

-- Double-entry ledger: every money movement is one transaction of entries that sum to zero.
-- Debits are positive, credits negative. Rows are only ever inserted; a mistake is fixed by a
-- new, reversing transaction, never by editing history.
CREATE TABLE ledger_transactions (
    id          uuid          PRIMARY KEY,
    payment_id  uuid          NOT NULL REFERENCES payments (id),
    -- The idempotency key of the provider call it books: booking the same capture twice is a no-op.
    reference   varchar(100)  NOT NULL,
    kind        varchar(16)   NOT NULL CHECK (kind IN ('CAPTURE')),
    booked_at   timestamptz   NOT NULL,
    CONSTRAINT ledger_transactions_reference_unique UNIQUE (reference)
);

CREATE TABLE ledger_entries (
    transaction_id  uuid           NOT NULL REFERENCES ledger_transactions (id),
    line_number     integer        NOT NULL CHECK (line_number > 0),
    account         varchar(32)    NOT NULL CHECK (account IN ('PROVIDER_RECEIVABLE', 'SALES')),
    amount          numeric(12, 2) NOT NULL CHECK (amount <> 0),
    currency        varchar(3)     NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    PRIMARY KEY (transaction_id, line_number)
);

CREATE INDEX ledger_entries_account ON ledger_entries (account, currency);

-- The database keeps the two ledger rules itself, whatever code writes to it.

-- 1. Balanced: a transaction's entries are inserted in one statement and must sum to zero in one currency.
CREATE FUNCTION ledger_transactions_balance() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    unbalanced uuid;
BEGIN
    SELECT e.transaction_id INTO unbalanced
    FROM ledger_entries e
    WHERE e.transaction_id IN (SELECT transaction_id FROM new_entries)
    GROUP BY e.transaction_id
    HAVING sum(e.amount) <> 0 OR count(DISTINCT e.currency) > 1 OR count(*) < 2
    LIMIT 1;
    IF unbalanced IS NOT NULL THEN
        RAISE EXCEPTION 'Ledger transaction % does not balance', unbalanced;
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER ledger_entries_balance
    AFTER INSERT ON ledger_entries
    REFERENCING NEW TABLE AS new_entries
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_transactions_balance();

-- 2. Append-only: history is never changed.
CREATE FUNCTION ledger_is_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'The ledger is append-only: % on % is not allowed', TG_OP, TG_TABLE_NAME;
END;
$$;

CREATE TRIGGER ledger_transactions_append_only
    BEFORE UPDATE OR DELETE ON ledger_transactions
    FOR EACH ROW EXECUTE FUNCTION ledger_is_append_only();

CREATE TRIGGER ledger_entries_append_only
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_is_append_only();

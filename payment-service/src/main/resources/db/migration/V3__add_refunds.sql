-- Refunds (ADR-0010): one per return; together never more than the captured amount.

-- Refunded or being refunded, kept on the payment and limited by the database. A refund adds to it
-- in one conditional statement before the provider is called; a declined refund takes it back.
ALTER TABLE payments ADD COLUMN refunded_amount numeric(12, 2) NOT NULL DEFAULT 0;
ALTER TABLE payments ADD CONSTRAINT payments_refunds_within_amount
    CHECK (refunded_amount >= 0 AND refunded_amount <= amount);

CREATE TABLE refunds (
    id                  uuid           PRIMARY KEY,
    payment_id          uuid           NOT NULL REFERENCES payments (id),
    order_id            uuid           NOT NULL,
    -- One refund per return, whatever happens to the commands that ask for it.
    return_id           uuid           NOT NULL UNIQUE,
    amount              numeric(12, 2) NOT NULL CHECK (amount > 0),
    currency            varchar(3)     NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    status              varchar(16)    NOT NULL CHECK (status IN ('REFUNDING', 'REFUNDED', 'FAILED')),
    provider_reference  varchar(64),
    failure_reason      varchar(500),
    created_at          timestamptz    NOT NULL,
    updated_at          timestamptz    NOT NULL
);

CREATE INDEX refunds_in_flight ON refunds (updated_at) WHERE status = 'REFUNDING';

-- A refund is booked as a new transaction reversing the capture's accounts.
ALTER TABLE ledger_transactions DROP CONSTRAINT ledger_transactions_kind_check;
ALTER TABLE ledger_transactions ADD CONSTRAINT ledger_transactions_kind_check CHECK (kind IN ('CAPTURE', 'REFUND'));

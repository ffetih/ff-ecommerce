-- Returns (ADR-0010). The return window starts at delivery, which updated_at no longer tells
-- (payment changes move it), so delivery gets its own column.
ALTER TABLE orders ADD COLUMN delivered_at timestamptz;

CREATE TABLE returns (
    id                uuid           PRIMARY KEY,
    order_id          uuid           NOT NULL REFERENCES orders (id),
    customer_id       uuid           NOT NULL,
    status            varchar(16)    NOT NULL
        CHECK (status IN ('REQUESTED', 'RECEIVED', 'REJECTED', 'REFUNDED', 'REFUND_FAILED')),
    refund_amount     numeric(12, 2) NOT NULL CHECK (refund_amount >= 0),
    currency          varchar(3)     NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    reason            varchar(500)   NOT NULL,
    rejection_reason  varchar(500),
    -- The client's key per return attempt: a double click creates one return, not two.
    idempotency_key   uuid           NOT NULL,
    request_hash      varchar(64)    NOT NULL,
    created_at        timestamptz    NOT NULL,
    updated_at        timestamptz    NOT NULL,
    version           bigint         NOT NULL DEFAULT 0,
    CONSTRAINT returns_idempotency_key_unique UNIQUE (order_id, idempotency_key)
);

CREATE INDEX returns_by_order ON returns (order_id, created_at);

CREATE TABLE return_lines (
    return_id    uuid           NOT NULL REFERENCES returns (id),
    line_number  integer        NOT NULL CHECK (line_number > 0),
    sku          varchar(64)    NOT NULL,
    quantity     integer        NOT NULL CHECK (quantity > 0),
    unit_price   numeric(12, 2) NOT NULL CHECK (unit_price >= 0),
    PRIMARY KEY (return_id, line_number)
);

-- "Never more than was bought", kept by the database: one row per order line that has returns,
-- counting the units of returns that are not rejected. A request adds to it in one conditional
-- statement; two concurrent requests cannot both pass the limit, and the check refuses the rest.
CREATE TABLE returned_units (
    order_id   uuid         NOT NULL REFERENCES orders (id),
    sku        varchar(64)  NOT NULL,
    ordered    integer      NOT NULL CHECK (ordered > 0),
    returned   integer      NOT NULL,
    PRIMARY KEY (order_id, sku),
    CONSTRAINT returned_units_within_order CHECK (returned >= 0 AND returned <= ordered)
);

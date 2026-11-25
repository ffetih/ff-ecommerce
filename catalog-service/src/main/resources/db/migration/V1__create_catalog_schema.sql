-- Products and their current price.
CREATE TABLE products (
    sku            varchar(64)    PRIMARY KEY,
    name           varchar(255)   NOT NULL,
    unit_price     numeric(12, 2) NOT NULL CHECK (unit_price >= 0),
    currency       varchar(3)     NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    active         boolean        NOT NULL,
    created_at     timestamptz    NOT NULL,
    updated_at     timestamptz    NOT NULL
);

-- One stock row per product. available = on_hand - reserved.
-- The CHECK constraints are a last line of defence: even a buggy code path cannot
-- commit negative stock or reserve more than is physically on hand.
CREATE TABLE stock_items (
    sku            varchar(64)    PRIMARY KEY REFERENCES products (sku),
    on_hand        integer        NOT NULL CHECK (on_hand >= 0),
    reserved       integer        NOT NULL CHECK (reserved >= 0),
    updated_at     timestamptz    NOT NULL,
    CONSTRAINT stock_reserved_within_on_hand CHECK (reserved <= on_hand)
);

-- Goods receipts, keyed by the caller's reference so a retried receipt is applied once.
CREATE TABLE stock_receipts (
    reference_id   uuid           PRIMARY KEY,
    sku            varchar(64)    NOT NULL REFERENCES products (sku),
    quantity       integer        NOT NULL CHECK (quantity > 0),
    received_at    timestamptz    NOT NULL
);

-- Stock reservations, one per order (the order id is the idempotency key).
CREATE TABLE reservations (
    order_id       uuid           PRIMARY KEY,
    status         varchar(16)    NOT NULL CHECK (status IN ('RESERVED', 'COMMITTED', 'RELEASED')),
    created_at     timestamptz    NOT NULL,
    updated_at     timestamptz    NOT NULL
);

CREATE TABLE reservation_lines (
    order_id       uuid           NOT NULL REFERENCES reservations (order_id),
    sku            varchar(64)    NOT NULL REFERENCES products (sku),
    quantity       integer        NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (order_id, sku)
);

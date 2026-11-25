CREATE TABLE orders (
    id                   uuid           PRIMARY KEY,
    customer_id          uuid           NOT NULL,
    status               varchar(16)    NOT NULL
        CHECK (status IN ('PLACED', 'CONFIRMED', 'SHIPPED', 'DELIVERED', 'CANCELLED')),
    currency             varchar(3)     NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    total_amount         numeric(12, 2) NOT NULL CHECK (total_amount >= 0),
    shipping_address     varchar(500)   NOT NULL,
    cancellation_reason  varchar(500),
    -- Client-chosen key that makes "place order" safe to retry (see OrderService.place).
    idempotency_key      uuid           NOT NULL,
    request_hash         varchar(64)    NOT NULL,
    created_at           timestamptz    NOT NULL,
    updated_at           timestamptz    NOT NULL,
    version              bigint         NOT NULL DEFAULT 0,
    CONSTRAINT orders_idempotency_key_unique UNIQUE (customer_id, idempotency_key)
);

CREATE INDEX orders_by_customer ON orders (customer_id, created_at DESC);

-- Prices are copied from the catalog when the order is placed and never change afterwards.
CREATE TABLE order_lines (
    order_id     uuid           NOT NULL REFERENCES orders (id),
    line_number  integer        NOT NULL CHECK (line_number > 0),
    sku          varchar(64)    NOT NULL,
    quantity     integer        NOT NULL CHECK (quantity > 0),
    unit_price   numeric(12, 2) NOT NULL CHECK (unit_price >= 0),
    PRIMARY KEY (order_id, line_number)
);

CREATE TABLE shipments (
    id                uuid           PRIMARY KEY,
    order_id          uuid           NOT NULL,
    customer_id       uuid           NOT NULL,
    shipping_address  varchar(500)   NOT NULL,
    status            varchar(16)    NOT NULL CHECK (status IN ('CREATED', 'DISPATCHED', 'DELIVERED', 'CANCELLED')),
    tracking_number   varchar(32),
    created_at        timestamptz    NOT NULL,
    updated_at        timestamptz    NOT NULL,
    version           bigint         NOT NULL DEFAULT 0,
    -- One shipment per order, whatever happens to the events that create it.
    CONSTRAINT shipments_order_unique UNIQUE (order_id),
    CONSTRAINT shipments_tracking_unique UNIQUE (tracking_number)
);

CREATE TABLE shipment_lines (
    shipment_id   uuid          NOT NULL REFERENCES shipments (id),
    line_number   integer       NOT NULL CHECK (line_number > 0),
    sku           varchar(64)   NOT NULL,
    quantity      integer       NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (shipment_id, line_number)
);

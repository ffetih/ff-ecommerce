-- Orders whose cancellation shipping accepted before it had a shipment for them (ADR-0007).
-- An order-confirmed that arrives later (a replay from a dead-letter topic) must not create one.
CREATE TABLE cancelled_orders (
    order_id      uuid          PRIMARY KEY,
    cancelled_at  timestamptz   NOT NULL
);

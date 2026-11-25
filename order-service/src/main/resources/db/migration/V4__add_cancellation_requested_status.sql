-- A confirmed order is no longer cancelled by order-service alone: it asks shipping first (ADR-0007).
-- The waiting state's name needs a longer column.
ALTER TABLE orders ALTER COLUMN status TYPE varchar(32);

ALTER TABLE orders DROP CONSTRAINT orders_status_check;
ALTER TABLE orders ADD CONSTRAINT orders_status_check
    CHECK (status IN ('PLACED', 'CONFIRMED', 'CANCELLATION_REQUESTED', 'SHIPPED', 'DELIVERED', 'CANCELLED'));

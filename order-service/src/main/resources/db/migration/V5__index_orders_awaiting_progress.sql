-- Order expiry scans for orders still PLACED or CONFIRMED, oldest first. Only those two states are
-- indexed, so shipped, delivered and cancelled orders (most of the table over time) cost nothing here.
CREATE INDEX orders_awaiting_progress ON orders (status, updated_at)
    WHERE status IN ('PLACED', 'CONFIRMED');

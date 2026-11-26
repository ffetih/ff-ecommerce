-- Payments (ADR-0009): the order carries the customer's payment method and what order-service has
-- asked of payment-service. Orders placed before payments existed keep NOT_REQUESTED and a null
-- token: they need no payment and ship as before.
ALTER TABLE orders ADD COLUMN card_token varchar(64);
ALTER TABLE orders ADD COLUMN payment_status varchar(16) NOT NULL DEFAULT 'NOT_REQUESTED'
    CHECK (payment_status IN ('NOT_REQUESTED', 'AUTHORIZING', 'AUTHORIZED', 'DECLINED', 'VOIDING', 'VOIDED',
                              'CAPTURING', 'CAPTURED', 'OVERDUE'));

-- Operations look for orders whose money is owed: shipped, but the capture failed.
CREATE INDEX orders_payment_overdue ON orders (updated_at) WHERE payment_status = 'OVERDUE';

-- W3C trace context of the work that wrote each outbox row. The relay sends the event later, from its own
-- thread; with this value the Kafka message continues the original trace (ADR-0011). NULL when tracing is off.
ALTER TABLE outbox_messages ADD COLUMN traceparent text;

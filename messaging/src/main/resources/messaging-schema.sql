-- Tables required by the messaging module.
-- Each service owns its schema (ADR-0002), so services copy this DDL into their own Flyway migration.
-- IF NOT EXISTS keeps this file usable for the module's own tests.

-- Events written in the same transaction as the business change, relayed to Kafka afterwards.
CREATE TABLE IF NOT EXISTS outbox_messages (
    id               bigserial      PRIMARY KEY,
    event_id         uuid           NOT NULL UNIQUE,
    topic            varchar(255)   NOT NULL,
    message_key      varchar(255)   NOT NULL,
    event_type       varchar(255)   NOT NULL,
    payload          jsonb          NOT NULL,
    created_at       timestamptz    NOT NULL DEFAULT now(),
    attempts         integer        NOT NULL DEFAULT 0,
    next_attempt_at  timestamptz    NOT NULL DEFAULT now(),
    last_error       text,
    published_at     timestamptz
);

-- The relay only ever looks at unpublished rows, per (topic, key) in id order.
CREATE INDEX IF NOT EXISTS outbox_messages_pending
    ON outbox_messages (topic, message_key, id) WHERE published_at IS NULL;

-- Retention cleanup deletes old published rows.
CREATE INDEX IF NOT EXISTS outbox_messages_published_at
    ON outbox_messages (published_at) WHERE published_at IS NOT NULL;

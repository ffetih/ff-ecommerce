-- Transactional outbox (ADR-0005), copied from messaging/src/main/resources/messaging-schema.sql.
CREATE TABLE outbox_messages (
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

CREATE INDEX outbox_messages_pending
    ON outbox_messages (topic, message_key, id) WHERE published_at IS NULL;

CREATE INDEX outbox_messages_published_at
    ON outbox_messages (published_at) WHERE published_at IS NOT NULL;

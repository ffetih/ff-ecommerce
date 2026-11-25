-- Consumer inbox (ADR-0005), copied from messaging/src/main/resources/messaging-schema.sql.
CREATE TABLE inbox_messages (
    consumer         varchar(100)   NOT NULL,
    event_id         uuid           NOT NULL,
    processed_at     timestamptz    NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);

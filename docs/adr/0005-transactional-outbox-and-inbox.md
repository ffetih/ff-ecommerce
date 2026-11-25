# ADR-0005: Transactional outbox with ordered relay, and consumer inbox

- **Status:** Accepted
- **Date:** 2026-11-25

## Context

A service must change its database and publish an event about that change. Writing to the database
and to Kafka are two separate systems with no shared transaction, so either one can succeed while the
other fails. Consumers also need events of one entity (for example one SKU) in the order they happened.

The previous version had an outbox, but its relay processed a batch in creation order and moved on
when a row failed, so a later event of the same entity could overtake an earlier one. Rows that failed
ten times were parked as `FAILED` and never looked at again, and two relay instances would publish the
same rows twice.

## Decision

**Outbox (producer side).** The event is inserted into `outbox_messages` in the same database
transaction as the business change. `Outbox.append` refuses to run without an active transaction.

**Relay.** A background relay publishes rows to Kafka:

- It claims the oldest unpublished row of each `(topic, key)` with `FOR UPDATE SKIP LOCKED`, then locks
  the rows queued behind it for the same key. Any number of relay instances can run; they share work
  by key and never publish the same row in parallel.
- Within a key it sends one row at a time. On the first failure it stops that key; later rows of the
  key wait. Other keys continue.
- Failed rows are retried with exponential backoff (capped). They are never dropped: an event that is
  silently lost leaves services permanently inconsistent, whereas a stuck key is visible
  (`attempts`, `last_error`, age of the oldest pending row) and recovers by itself once the cause is fixed.
- Published rows are deleted after a retention period (default 7 days).

Per-key order equals commit order because writers of one aggregate are serialized by its row lock, so
the outbox row ids of one key are assigned in commit order.

**Inbox (consumer side).** Delivery is at least once. A consumer records `(consumer, event_id)` in
`inbox_messages` in the same transaction as its own change, and skips events it has already recorded.

**Failed consumption.** A record whose processing throws is retried in place a few times with growing
pauses (0.5 s, 1 s, 2 s), then published to `<topic>-dlt` so the partition can move on. Messages that
cannot be parsed skip the retries. The dead-letter topic name is set explicitly rather than taken from
the framework default, which changed between Spring Kafka major versions. Dead-letter topics must exist
with at least as many partitions as their source; if publishing to one fails, the record is retried
rather than skipped, so the partition stops until the topic is fixed. Values are read as strings through `ErrorHandlingDeserializer`, so even
undecodable bytes reach the error handler instead of failing inside the consumer's poll loop.

**Schema.** The DDL lives in `messaging/src/main/resources/messaging-schema.sql`; each service copies it
into its own Flyway migration, keeping full ownership of its schema.

## Consequences

- No distributed transaction is needed, and no event is lost or reordered per key.
- Consumers must be idempotent; the inbox makes that a one-line check.
- One poisoned event blocks its own key until fixed. This is deliberate and needs monitoring: the age
  of the oldest unpublished row and the dead-letter count arrive in s06, dashboards and alerts in s09.
- Events are delivered with a small delay (the poll interval).

## Alternatives considered

- **Publish to Kafka after commit:** loses events when the process dies between commit and send.
- **Kafka transactions:** atomic only within Kafka, not across PostgreSQL and Kafka.
- **Change data capture (Debezium):** strong option, but adds Kafka Connect to operate; the outbox
  table makes a later switch to CDC straightforward.
- **Single active relay via leader election:** simpler ordering, but no parallelism and a failover gap.

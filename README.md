# E-Commerce Microservices

![CI](https://github.com/ffetih/ff-ecommerce/actions/workflows/ci.yml/badge.svg)

A reference implementation of an order-to-delivery flow built as independent services:
catalog and inventory, orders, shipping, payments and returns.

The focus is on the hard parts of distributed systems rather than on feature count:
consistent stock under concurrency, a saga with correct compensation, reliable event
publishing with the transactional outbox, idempotent consumers, and a real security boundary.

**Stack:** Java 25 · Spring Boot 4.2 · PostgreSQL 17 · Apache Kafka (KRaft) · Testcontainers

## Status

The project is built sprint by sprint. Each sprint ends with a green build, a tag (`s00`, `s01`, ...)
and tests that prove what the sprint claims.

| Sprint | Scope | Status |
| --- | --- | --- |
| s00 | Foundation: build, local infrastructure, CI | done |
| s01 | Catalog & inventory: server-side prices, concurrency-safe stock reservations | done |
| s02 | Messaging: transactional outbox with ordered relay, consumer inbox, dead-letter topics | done |

See [docs/SPRINT_PLAN.md](docs/SPRINT_PLAN.md) for the full roadmap and
[docs/adr](docs/adr) for architecture decisions.

## Services

| Service | Port | Responsibility |
| --- | --- | --- |
| `catalog-service` | 8081 | Products and prices; stock receipts; reserve / commit / release stock per order |

### catalog-service API

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/v1/products` | Create a product (with an empty stock row) |
| `GET` | `/api/v1/products?sku=A&sku=B` | Current prices for the given SKUs |
| `PUT` | `/api/v1/products/{sku}/price` | Change a price |
| `POST` | `/api/v1/stock/{sku}/receipts` | Goods receipt, idempotent by `referenceId` |
| `GET` | `/api/v1/stock/{sku}` | `onHand`, `reserved`, `available` |
| `PUT` | `/api/v1/reservations/{orderId}` | Reserve all order lines or none; idempotent by order id |
| `POST` | `/api/v1/reservations/{orderId}/commit` | Order shipped: reserved units leave stock |
| `POST` | `/api/v1/reservations/{orderId}/release` | Order cancelled: reserved units become available |

Every stock change is published as a `catalog.stock-changed` event on topic `catalog.stock`
(key: SKU), recorded in the same transaction as the change.

Errors are RFC 9457 problem responses (`application/problem+json`). The API is unauthenticated
until the security sprint (s05).

## Messaging

The `messaging` module gives every service the same guarantees ([ADR-0005](docs/adr/0005-transactional-outbox-and-inbox.md),
[ADR-0006](docs/adr/0006-event-format.md)):

- **Outbox:** events are written in the business transaction and relayed to Kafka afterwards; nothing is
  published for a rolled-back change, and nothing is lost if Kafka is down.
- **Ordered relay:** events of one key are published in commit order; relay instances share work with
  `FOR UPDATE SKIP LOCKED`; a failing event blocks only its own key and is retried with backoff.
- **Inbox:** consumers record processed event ids in their own transaction, so redeliveries have no effect.
- **Dead letters:** failing records are retried briefly, then moved to `<topic>-dlt`; unreadable ones go there immediately.

## Build and test

Requires JDK 25 and Docker.

```bash
./mvnw verify          # unit tests (*Test) + integration tests against real containers (*IT)
./mvnw test            # unit tests only
```

## Local infrastructure

```bash
docker compose up -d                      # PostgreSQL 17 on :5432, Kafka on :9092
docker compose --profile tools up -d      # adds Kafka UI on http://localhost:8090
docker compose down                       # stop (add -v to also delete data)
```

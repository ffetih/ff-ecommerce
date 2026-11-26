# E-Commerce Microservices

![CI](https://github.com/ffetih/ff-ecommerce/actions/workflows/ci.yml/badge.svg)

An order-to-delivery flow built as independent services: catalog and inventory, customers, orders
and shipping, each with its own database, talking through Kafka. The focus is on the hard parts of
distributed systems rather than on feature count: consistent stock under concurrency, a saga with
correct compensation, reliable event publishing with the transactional outbox, idempotent
consumers, and a real security boundary.

**Documentation: [ffetih.github.io/ff-ecommerce-docs](https://ffetih.github.io/ff-ecommerce-docs/ff-ecommerce/index.html)**, by role:
[architect](https://ffetih.github.io/ff-ecommerce-docs/ff-ecommerce/architecture/index.html) ·
[business](https://ffetih.github.io/ff-ecommerce-docs/ff-ecommerce/business/index.html) ·
[developer](https://ffetih.github.io/ff-ecommerce-docs/ff-ecommerce/developer/index.html) ·
[operations](https://ffetih.github.io/ff-ecommerce-docs/ff-ecommerce/operations/index.html).
Start with [seven failure stories](https://ffetih.github.io/ff-ecommerce-docs/ff-ecommerce/architecture/consistency/index.html): each rule in the saga
exists because of one concrete way the system could end up wrong.

**Stack:** Java 25 · Spring Boot 4.2 · PostgreSQL 17 · Apache Kafka (KRaft) · Keycloak 26 · Testcontainers

## Services

| Service | Port | Responsibility |
| --- | --- | --- |
| `catalog-service` | 8081 | Products and prices; stock receipts; reserve / commit / release stock per order |
| `customer-service` | 8082 | Customer profiles keyed by the Keycloak user id; shipping addresses; deactivation |
| `order-service` | 8083 | Order placement at catalog prices, idempotent per `Idempotency-Key`; order lifecycle and expiry |
| `shipping-service` | 8084 | One shipment per confirmed order; dispatch and delivery; decides cancellations of confirmed orders |
| `gateway` | 8080 | Single entry point: routes each API path to its service; makes no authorization decision |

API: [reference](https://ffetih.github.io/ff-ecommerce-docs/ff-ecommerce/developer/api.html). Saga: [how it works](https://ffetih.github.io/ff-ecommerce-docs/ff-ecommerce/architecture/saga.html).

## Quick start

Requires JDK 25 and Docker.

```bash
docker compose up -d --wait               # PostgreSQL :5432, Kafka :9092, Keycloak :8180
./mvnw verify                             # unit, integration and end-to-end tests
```

Running the services, tokens and a walk-through of one order:
[local run](https://ffetih.github.io/ff-ecommerce-docs/ff-ecommerce/developer/local-run.html),
[try it](https://ffetih.github.io/ff-ecommerce-docs/ff-ecommerce/developer/try-it/order-to-delivery.html).

## Status

Built sprint by sprint; each sprint ends with a green build, a tag (`s00`, `s01`, ...), tests that
prove what it claims, and updated documentation.

| Sprint | Scope | Status |
| --- | --- | --- |
| s00 | Foundation: build, local infrastructure, CI | done |
| s01 | Catalog & inventory: server-side prices, concurrency-safe stock reservations | done |
| s02 | Messaging: transactional outbox with ordered relay, consumer inbox, dead-letter topics | done |
| s03 | Customers and orders: catalog-priced, idempotent order placement; forward-only order status | done |
| s04 | Shipping and the order saga: reserve, confirm, ship, deliver, cancel with compensation; end-to-end tests | done |
| s05 | Security: Keycloak, resource servers with enforced deny-by-default, identity from the token, service tokens, routing-only gateway | done |
| s06 | Saga hardening: cancellation decided by shipping, delivery only after dispatch, order expiry, metrics, replay guards, inbox retention | done |
| s07 | Documentation: role-based site, domain model, failure stories with live drills, runbooks | done |
| s08 | Payments | next |
| s09 | Returns | planned |
| s10 | Observability | planned |
| s11 | Packaging | planned |

Full roadmap and architecture decisions: [roadmap](https://ffetih.github.io/ff-ecommerce-docs/ff-ecommerce/roadmap.html), [ADRs](https://ffetih.github.io/ff-ecommerce-docs/ff-ecommerce/architecture/adr/index.html).

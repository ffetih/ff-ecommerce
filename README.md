# E-Commerce Microservices

![CI](https://github.com/ffetih/ff-ecommerce/actions/workflows/ci.yml/badge.svg)

An order-to-delivery flow built as independent services: catalog and inventory, customers, orders,
payments and shipping, each with its own database, talking through Kafka. The focus is on the hard
parts of distributed systems rather than on feature count: consistent stock under concurrency, a saga
with correct compensation, reliable event publishing with the transactional outbox, idempotent
consumers, payments that never charge twice even when the provider does not answer, a double-entry
ledger, returns and refunds whose limits hold under concurrency, a real security boundary, and one
trace per order through HTTP, Kafka and the outbox.

**Documentation: [ffetih.github.io/ff-ecommerce-docs](https://ffetih.github.io/ff-ecommerce-docs/ff-ecommerce/index.html)**, by role:
[architect](https://ffetih.github.io/ff-ecommerce-docs/ff-ecommerce/architecture/index.html) ·
[business](https://ffetih.github.io/ff-ecommerce-docs/ff-ecommerce/business/index.html) ·
[developer](https://ffetih.github.io/ff-ecommerce-docs/ff-ecommerce/developer/index.html) ·
[operations](https://ffetih.github.io/ff-ecommerce-docs/ff-ecommerce/operations/index.html).
Start with [fourteen failure stories](https://ffetih.github.io/ff-ecommerce-docs/ff-ecommerce/architecture/consistency/index.html): each rule in the saga
exists because of one concrete way the system could end up wrong.

**Stack:** Java 25 · Spring Boot 4.2 · PostgreSQL 17 · Apache Kafka (KRaft) · Keycloak 26 · Testcontainers ·
OpenTelemetry · Jaeger · Prometheus · Grafana

## Services

| Service | Port | Responsibility |
| --- | --- | --- |
| `catalog-service` | 8081 | Products and prices; stock receipts; reserve / commit / release stock per order |
| `customer-service` | 8082 | Customer profiles keyed by the Keycloak user id; shipping addresses; deactivation |
| `order-service` | 8083 | Order placement at catalog prices, idempotent per `Idempotency-Key`; order lifecycle and expiry; drives the payment; returns within 14 days, never more than was bought |
| `shipping-service` | 8084 | One shipment per confirmed order; dispatch and delivery; decides cancellations of confirmed orders |
| `payment-service` | 8085 | Authorize, capture, void and refund through a (simulated) provider with idempotency keys; resolves unanswered calls; refunds never exceed the capture; double-entry ledger. No public API: commanded by order-service |
| `gateway` | 8080 | Single entry point: routes each API path to its service; makes no authorization decision (actuator on 8089) |

API: [reference](https://ffetih.github.io/ff-ecommerce-docs/ff-ecommerce/developer/api.html). Saga: [how it works](https://ffetih.github.io/ff-ecommerce-docs/ff-ecommerce/architecture/saga.html).

## Quick start

Requires JDK 25 and Docker.

```bash
docker compose up -d --wait               # PostgreSQL :5432, Kafka :9092, Keycloak :8180
./mvnw verify                             # unit, integration and end-to-end tests
docker compose --profile observability up -d   # optional: Jaeger :16686, Prometheus :9090, Grafana :3000
```

Or the whole system in containers, one command after building the images:

```bash
./mvnw -q package -DskipTests jib:dockerBuild
docker compose --profile app --profile observability up -d --wait
```

Running the services, tokens and a walk-through of one order:
[local run](https://ffetih.github.io/ff-ecommerce-docs/ff-ecommerce/developer/local-run.html),
[try it](https://ffetih.github.io/ff-ecommerce-docs/ff-ecommerce/developer/try-it/order-to-delivery.html),
[follow an order in Jaeger](https://ffetih.github.io/ff-ecommerce-docs/ff-ecommerce/operations/observability.html).

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
| s08 | Payments: authorize before confirming, capture at dispatch, void on cancellation; unknown provider outcomes resolved, never declined; double-entry ledger | done |
| s09 | Returns: within 14 days of delivery, never more than was bought (database-enforced per line); refund when the goods arrive back, never more than was captured; idempotent restock | done |
| s10 | Observability: one trace per order through HTTP, Kafka and the outbox; Prometheus scraping with its own client; Grafana dashboard and alert rules as code | done |
| s11 | Packaging: an image per service (Jib), published to GHCR from CI; the whole system with one command; one token issuer inside and outside containers | in progress |

Full roadmap and architecture decisions: [roadmap](https://ffetih.github.io/ff-ecommerce-docs/ff-ecommerce/roadmap.html), [ADRs](https://ffetih.github.io/ff-ecommerce-docs/ff-ecommerce/architecture/adr/index.html).

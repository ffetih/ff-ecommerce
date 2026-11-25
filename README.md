# E-Commerce Microservices

![CI](https://github.com/ffetih/ff-ecommerce/actions/workflows/ci.yml/badge.svg)

A reference implementation of an order-to-delivery flow built as independent services:
catalog and inventory, customers, orders and shipping. Payments and returns follow on the
[roadmap](docs/SPRINT_PLAN.md).

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
| s03 | Customers and orders: catalog-priced, idempotent order placement; forward-only order status | done |
| s04 | Shipping and the order saga: reserve, confirm, ship, deliver, cancel with compensation; end-to-end tests | done |
| s05 | Security: Keycloak, OAuth2 resource servers with enforced deny-by-default, identity and ownership from the token, service-to-service tokens, routing-only gateway | done |
| s06 | Saga hardening: cancellation decided by shipping, delivery only after dispatch, reservation expiry, outbox and dead-letter metrics, product deactivation, inbox retention | in progress |

See [docs/SPRINT_PLAN.md](docs/SPRINT_PLAN.md) for the full roadmap and
[docs/adr](docs/adr) for architecture decisions.

## Services

| Service | Port | Responsibility |
| --- | --- | --- |
| `catalog-service` | 8081 | Products and prices; stock receipts; reserve / commit / release stock per order |
| `customer-service` | 8082 | Customer accounts with unique, normalized emails; shipping addresses; deactivation |
| `order-service` | 8083 | Order placement at catalog prices, idempotent per `Idempotency-Key`; order lifecycle |
| `shipping-service` | 8084 | One shipment per confirmed order; dispatch and delivery; cancellation before dispatch |
| `gateway` | 8080 | Single entry point: routes each API path to its service; makes no authorization decision |

Clients call the gateway (`http://localhost:8080/api/v1/...`) with a Keycloak access token. The
gateway forwards the request and its `Authorization` header unchanged to the service that owns the
path, and forwards nothing else (no documentation proxying, no catch-all route). Every service
validates the token itself and decides access with deny-by-default rules, so a request that gets
around the gateway meets the same checks.

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

Errors are RFC 9457 problem responses (`application/problem+json`).

Access: reading products is public; every other endpoint requires the `admin` role. Calls carry a
Keycloak access token (`Authorization: Bearer …`) whose `aud` includes `catalog-service`; see
[infra/keycloak](infra/keycloak) and `scripts/dev-auth.sh`.

### customer-service API

| Method | Path | Who | Purpose |
| --- | --- | --- | --- |
| `POST` | `/api/v1/customers/me` | customer | Create the caller's profile (id and email come from the token) |
| `GET` | `/api/v1/customers/me` | customer | The caller's profile |
| `PUT` | `/api/v1/customers/me/shipping-address` | customer | Change the caller's shipping address |
| `GET` | `/api/v1/customers/{id}` | admin, service | Any profile (order-service checks customers here) |
| `POST` | `/api/v1/customers/{id}/deactivate` | admin | Deactivate a customer |

A customer's id is their Keycloak user id (`sub`), so there is no mapping table between logins and
customers.

### order-service API

| Method | Path | Who | Purpose |
| --- | --- | --- | --- |
| `POST` | `/api/v1/orders` | customer | Place an order for the caller; requires an `Idempotency-Key` header (UUID per order attempt) |
| `GET` | `/api/v1/orders/{orderId}` | owner, admin | One order with its lines and prices |
| `GET` | `/api/v1/orders` | customer | The caller's orders, newest first (admin: `?customerId=…` for anyone's) |
| `POST` | `/api/v1/orders/{orderId}/cancel` | owner, admin | Cancel: `200` if not confirmed yet; `202` for a confirmed order, while shipping decides; `409` once shipped |

The customer of an order is always the caller. Someone else's order answers `404`, like an unknown id,
so order ids cannot be probed. order-service calls customer-service and catalog-service with its own
client-credentials token, not the caller's.

Placing an order reads the customer and current prices synchronously (2 s connect / 3 s read timeouts),
then stores the order and an `order.order-placed` event in one transaction. Repeating a request with the
same key returns the same order (`200`, `Idempotent-Replayed: true`); the same key with a different body
is a `409`. Unknown or retired products and inactive customers give `422`; an unavailable dependency gives
`503` with `Retry-After`, and the request can be retried safely with the same key.

### shipping-service API

| Method | Path | Who | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/v1/shipments/{shipmentId}` | admin | One shipment with its lines and tracking number |
| `GET` | `/api/v1/shipments?orderId=…` | admin | The shipment of an order |
| `POST` | `/api/v1/shipments/{shipmentId}/dispatch` | admin | Hand over to the carrier: assigns the tracking number, commits the stock |
| `POST` | `/api/v1/shipments/{shipmentId}/deliver` | admin | Mark as delivered |

Shipments are created from `order.order-confirmed` events, not through the API. Customers cannot see
their shipment yet; exposing the tracking number on the order is on the roadmap.

## Security

Keycloak issues the tokens; each service checks them itself ([ADR-0008](docs/adr/0008-security.md)):

- **Token validation:** signature (keys discovered from the issuer), issuer, expiry, and an audience
  naming the service. A service without issuer or audience refuses to start.
- **Deny by default:** endpoints are listed with their allowed roles; everything else is denied. A
  service whose filter chains bypass the shared `security` module refuses to start.
- **Identity from the token:** the customer id is the token's `sub`; no request names a customer.
  Someone else's order answers `404`.
- **Service-to-service:** order-service calls customer and catalog with its own client-credentials
  token, cached, with timeouts, renewed once if rejected.
- **Gateway:** routes only; a request that bypasses it meets the same checks.

Known limits (development realm, unauthenticated Kafka, audience for people) are listed in the ADR.

## Order saga

Fulfilment is a choreographed saga across order, catalog and shipping ([ADR-0007](docs/adr/0007-order-saga.md)):

```
order-placed ──► catalog reserves ──► stock-reserved ──► order CONFIRMED ──► order-confirmed ──► shipment created
                         └──────────► reservation-failed ──► order CANCELLED
shipment dispatched ──► catalog commits stock, order SHIPPED ──► shipment delivered ──► order DELIVERED
customer cancels a placed order ──► order CANCELLED ──► order-cancelled ──► catalog releases stock
customer cancels a confirmed order ──► order CANCELLATION_REQUESTED ──► shipping decides:
    not dispatched ──► shipment cancelled ──► order CANCELLED ──► order-cancelled ──► catalog releases stock
    dispatched     ──► cancellation refused ──► order stays SHIPPED
```

Shipping decides cancellations of confirmed orders because only shipping knows whether the parcel has
left: a cancellation racing a dispatch ends either cancelled or shipped, never both
([ADR-0007](docs/adr/0007-order-saga.md)).

Business failures (not enough stock) are published as events in the same transaction as the inbox entry;
only technical failures roll back and are retried or dead-lettered ([ADR-0004](docs/adr/0004-explicit-transaction-boundaries.md)).

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
./mvnw verify                        # everything: unit, integration and end-to-end tests
./mvnw test                          # unit tests only
./mvnw verify -pl '!e2e-tests'       # skip the end-to-end tests
```

Three levels of tests:

- **Unit** (`*Test`): domain rules and application services with in-memory fakes; milliseconds.
- **Integration** (`*IT`): one service with real PostgreSQL and Kafka (Testcontainers); other services
  are played by the test, either as stub HTTP servers or by publishing their events.
- **End-to-end** (`e2e-tests`): the packaged services run as separate processes against shared containers,
  and the tests drive the whole saga through the public HTTP APIs only.

## Local infrastructure

```bash
docker compose up -d --wait               # PostgreSQL 17 on :5432, Kafka on :9092, Keycloak on :8180
docker compose --profile tools up -d      # adds Kafka UI on http://localhost:8090
docker compose down                       # stop (add -v to also delete data)
```

Keycloak runs the `ecommerce` realm from [infra/keycloak](infra/keycloak) (users alice, bob and
admin; the password is the username). To get and inspect tokens in a terminal:

```bash
source scripts/dev-auth.sh
jwt "$(token alice)"                      # claims of alice's access token
jwt "$(service-token order-service)"      # claims of order-service's machine token
```

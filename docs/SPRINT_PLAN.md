# Sprint Plan

Each sprint is a vertical slice. A sprint is done when:

- `./mvnw verify` is green locally and in CI,
- the sprint's main claim is proven by an integration test against real PostgreSQL/Kafka (Testcontainers),
- significant decisions are recorded as ADRs,
- the README status table is updated and the sprint is tagged (`sNN`).

## Roadmap

| Sprint | Goal | Key outcomes |
| --- | --- | --- |
| **s00** | Foundation | Maven parent, local infrastructure (PostgreSQL, Kafka), Testcontainers smoke test, CI |
| **s01** | Catalog & Inventory | Server-side prices; stock reserve / commit / release with row locking and idempotency keys; concurrency test |
| **s02** | Messaging backbone | Reusable transactional outbox (`FOR UPDATE SKIP LOCKED`, per-key ordering, retention), dead-letter topics, safe deserialization |
| **s03** | Customer & Order | Multi-line orders priced from the catalog; monotonic order state machine |
| **s04** | Shipping & Saga | Choreographed order saga with correct compensation and reservation release; end-to-end saga test |
| **s05** | Security | Keycloak realm as code; every service an OAuth2 resource server with audience checks and enforced deny-by-default; identity and ownership from the token; service-to-service client credentials; routing-only gateway ([ADR-0008](adr/0008-security.md)) |
| **s06** | Saga hardening | Cancellation as a request that shipping accepts or refuses (closes the cancel/dispatch race); delivery only after dispatch; order expiry for orders that are never confirmed or dispatched; metrics for the oldest unpublished outbox row and dead-lettered records; admin product deactivation; inbox retention longer than topic retention |
| **s07** | Payments | Authorize / capture / refund with idempotency keys and a double-entry ledger; payment step in the order flow, re-evaluating orchestration vs. choreography (ADR-0007) |
| **s08** | Returns | Concurrency-safe return limits; refunds through payments |
| **s09** | Observability | Metrics, distributed tracing, dashboards, alerts |
| **s10** | Packaging | Container images, one-command full stack, Kubernetes manifests, Kafka authentication |
| s11+ | Optional | Promotions, wishlist / back-in-stock notifications, shipment tracking for customers |

Saga hardening comes before payments on purpose: once money moves, a cancelled order whose parcel is
already on its way, or a delivery recorded without a dispatch, is a financial inconsistency rather
than a stock one. Those gaps are closed first, each proven by an integration test.

## Engineering conventions

- **Hexagonal architecture** per service: `domain` and `application` have no framework dependencies;
  adapters live in `adapter.in` / `adapter.out` ([ADR-0001](adr/0001-hexagonal-architecture.md)).
- **Database per service** with Flyway migrations and `ddl-auto: validate`
  ([ADR-0002](adr/0002-database-per-service.md)).
- **Tests:** unit tests are `*Test` (Surefire), integration tests are `*IT` (Failsafe, run in `verify`).
- **Git:** one branch per sprint, small commits, merged to `main` and tagged.

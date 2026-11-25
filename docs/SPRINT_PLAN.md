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
| **s05** | Security | API gateway, OAuth2/OIDC with Keycloak, resource ownership checks, service-to-service tokens |
| **s06** | Payments | Authorize / capture / refund with idempotency keys and a double-entry ledger; payment step in the saga |
| **s07** | Returns | Concurrency-safe return limits; refunds through payments |
| **s08** | Observability | Metrics, distributed tracing, dashboards |
| **s09** | Packaging | Container images, one-command full stack, Kubernetes manifests |
| s10+ | Optional | Promotions, wishlist / back-in-stock notifications |

## Engineering conventions

- **Hexagonal architecture** per service: `domain` and `application` have no framework dependencies;
  adapters live in `adapter.in` / `adapter.out` ([ADR-0001](adr/0001-hexagonal-architecture.md)).
- **Database per service** with Flyway migrations and `ddl-auto: validate`
  ([ADR-0002](adr/0002-database-per-service.md)).
- **Tests:** unit tests are `*Test` (Surefire), integration tests are `*IT` (Failsafe, run in `verify`).
- **Git:** one branch per sprint, small commits, merged to `main` and tagged.

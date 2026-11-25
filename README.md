# E-Commerce Microservices

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
| s00 | Foundation: build, local infrastructure, CI | in progress |

See [docs/SPRINT_PLAN.md](docs/SPRINT_PLAN.md) for the full roadmap and
[docs/adr](docs/adr) for architecture decisions.

## Build

Requires JDK 25 and Docker (for integration tests).

```bash
./mvnw verify
```

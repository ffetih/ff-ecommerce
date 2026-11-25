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

See [docs/SPRINT_PLAN.md](docs/SPRINT_PLAN.md) for the full roadmap and
[docs/adr](docs/adr) for architecture decisions.

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

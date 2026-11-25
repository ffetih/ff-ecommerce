# ADR-0007: Order fulfilment as a choreographed saga

- **Status:** Accepted
- **Date:** 2026-11-25

## Context

Fulfilling an order touches three services with their own databases: order (status), catalog (stock)
and shipping (parcel). There is no transaction across them. Each step can fail independently, and
messages can be delivered twice or late.

## Decision

The order lifecycle is a **choreographed saga**: each service reacts to events and publishes its own,
using the outbox (ADR-0005) and the inbox for idempotency.

```
order-service        catalog-service              shipping-service
─────────────        ───────────────              ────────────────
order-placed ──────► reserve stock
                     ├─ stock-reserved ─────┐
                     └─ reservation-failed ─┤
CONFIRMED ◄──────────────────── reserved ───┘
CANCELLED ◄──────────────── failed
order-confirmed ─────────────────────────────────► create shipment
                                                    dispatch ──► shipment-dispatched
                     commit reservation ◄────────────────────────┤
SHIPPED   ◄──────────────────────────────────────────────────────┘
                                                    deliver ───► shipment-delivered
DELIVERED ◄──────────────────────────────────────────────────────┘

customer cancels (PLACED or CONFIRMED):
order-cancelled ───► release reservation (if any)
                └──────────────────────────────────► cancel shipment (if not dispatched)
```

Topics and keys: `order.lifecycle`, `catalog.reservations`, `shipping.shipments`, all keyed by order id,
so the events of one order are consumed in order within each topic.

**Compensation rules**

| Situation | Compensation |
| --- | --- |
| Stock cannot be reserved | Catalog publishes `reservation-failed`; order is cancelled. Nothing was held. |
| Customer cancels a confirmed order | `order-cancelled`; catalog releases, shipping cancels the not-yet-dispatched shipment. |
| `stock-reserved` arrives for an order that was cancelled meanwhile | Order publishes `order-cancelled` again so catalog releases the late reservation. |
| Shipment already dispatched | The order cannot be cancelled any more; it becomes a return (later sprint). |

**Business failures are results, not exceptions.** A participant that fails a step for a business
reason (insufficient stock) records the failure event in the *same* transaction as its inbox entry and
commits. Exceptions are reserved for technical failures, which roll everything back and are retried or
dead-lettered. This is the rule that would have prevented the previous version's lost compensation
(ADR-0004).

**Known limitation: cancel versus dispatch.** Dispatch is the saga's point of no return. If a customer
cancels a confirmed order at the same moment the warehouse dispatches it, order-service has already
accepted the cancellation while the parcel is on its way. Catalog and order-service then receive
`shipment-dispatched` for a released reservation and a cancelled order; both refuse it, and the event lands
in the dead-letter topic where it is visible for manual handling (a return). The proper fix is to make
cancellation of a confirmed order a *request* that shipping accepts or rejects before order-service
commits to it; it is deferred until it is worth the extra round trip.

## Consequences

- No coordinator to operate, and each service stays usable when another is down; events wait in Kafka.
- The overall flow is only visible in this document and in tracing (s08), not in one class.
- Every handler must be idempotent and tolerate late events; the order state machine ignores steps it
  has already passed.

## Alternatives considered

- **Orchestration (a saga orchestrator in order-service):** the flow lives in one place and is easier to
  change, at the cost of a central component that knows every participant. A good choice when the flow
  grows (for example with payments in s06); revisit then.
- **Synchronous calls with compensation on error:** simple to read, but couples availability: an order
  fails whenever any participant is down.

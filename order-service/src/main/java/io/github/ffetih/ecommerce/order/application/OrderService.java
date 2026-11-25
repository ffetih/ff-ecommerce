package io.github.ffetih.ecommerce.order.application;

import io.github.ffetih.ecommerce.order.application.port.CustomerDirectory;
import io.github.ffetih.ecommerce.order.application.port.OrderEvents;
import io.github.ffetih.ecommerce.order.application.port.OrderRepository;
import io.github.ffetih.ecommerce.order.application.port.PriceCatalog;
import io.github.ffetih.ecommerce.order.application.port.Transactions;
import io.github.ffetih.ecommerce.order.domain.Order;
import io.github.ffetih.ecommerce.order.domain.OrderLine;
import io.github.ffetih.ecommerce.order.domain.Sku;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class OrderService {

    /** Result of placing an order; {@code replayed} is true when an earlier identical request is answered again. */
    public record Placement(Order order, boolean replayed) {
    }

    private final OrderRepository orders;
    private final CustomerDirectory customers;
    private final PriceCatalog catalog;
    private final OrderEvents events;
    private final Transactions transactions;
    private final Clock clock;

    public OrderService(OrderRepository orders, CustomerDirectory customers, PriceCatalog catalog,
                        OrderEvents events, Transactions transactions, Clock clock) {
        this.orders = orders;
        this.customers = customers;
        this.catalog = catalog;
        this.events = events;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Places an order, at most once per (customer, idempotency key).
     * <ol>
     *   <li>Answered before? Return that order again (or reject a different request under the same key).</li>
     *   <li>Ask customer-service and catalog-service. This happens <em>outside</em> any database
     *       transaction, so no connection is held while waiting on the network.</li>
     *   <li>Store the order and its {@code order-placed} event in one transaction.</li>
     * </ol>
     * Two identical requests racing past step 1 are settled by the unique key in the database: the
     * loser returns the winner's order.
     */
    public Placement place(PlaceOrderCommand command, UUID idempotencyKey) {
        String fingerprint = command.fingerprint();
        Optional<Placement> earlier = replay(command.customerId(), idempotencyKey, fingerprint);
        if (earlier.isPresent()) {
            return earlier.get();
        }

        CustomerDirectory.CustomerSnapshot customer = customers.find(command.customerId())
                .orElseThrow(() -> new OrderRejectedException("Unknown customer " + command.customerId()));
        if (!customer.active()) {
            throw new OrderRejectedException("Customer " + command.customerId() + " is deactivated");
        }
        Order order = Order.place(customer.id(), pricedLines(command.lines()),
                command.shippingAddress() != null ? command.shippingAddress() : customer.shippingAddress(),
                clock.instant());

        try {
            return transactions.inTransaction(() -> {
                orders.insert(order, idempotencyKey, fingerprint);
                events.orderPlaced(order);
                return new Placement(order, false);
            });
        } catch (DuplicateIdempotencyKeyException lostTheRace) {
            return replay(command.customerId(), idempotencyKey, fingerprint)
                    .orElseThrow(() -> new IllegalStateException("Duplicate key reported but no order found", lostTheRace));
        }
    }

    /**
     * Someone else's order is reported exactly like a missing one: answering "forbidden" would
     * confirm that the id exists, and order ids would become something to probe for.
     */
    public Order get(UUID orderId, Requester requester) {
        return transactions.inTransaction(() -> orders.findById(orderId)
                .filter(order -> requester.mayActFor(order.customerId()))
                .orElseThrow(() -> new OrderNotFoundException(orderId)));
    }

    public List<Order> forCustomer(UUID customerId, Requester requester) {
        if (!requester.mayActFor(customerId)) {
            throw new NotAllowedException("Customers can only list their own orders");
        }
        return transactions.inTransaction(() -> orders.findByCustomer(customerId));
    }

    private Optional<Placement> replay(UUID customerId, UUID idempotencyKey, String fingerprint) {
        Optional<OrderRepository.StoredOrder> stored =
                transactions.inTransaction(() -> orders.findByIdempotencyKey(customerId, idempotencyKey));
        return stored.map(earlier -> {
            if (!earlier.requestHash().equals(fingerprint)) {
                throw new IdempotencyKeyReusedException(idempotencyKey);
            }
            return new Placement(earlier.order(), true);
        });
    }

    private List<OrderLine> pricedLines(List<PlaceOrderCommand.Line> lines) {
        Map<Sku, PriceCatalog.ProductPrice> prices = catalog.currentPrices(
                lines.stream().map(PlaceOrderCommand.Line::sku).toList());
        return lines.stream().map(line -> {
            PriceCatalog.ProductPrice price = prices.get(line.sku());
            if (price == null) {
                throw new OrderRejectedException("Unknown product " + line.sku());
            }
            if (!price.active()) {
                throw new OrderRejectedException("Product " + line.sku() + " is no longer sold");
            }
            return new OrderLine(line.sku(), line.quantity(), price.unitPrice());
        }).toList();
    }
}

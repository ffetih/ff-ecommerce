package io.github.ffetih.ecommerce.order.application;

import io.github.ffetih.ecommerce.order.application.port.CustomerDirectory;
import io.github.ffetih.ecommerce.order.application.port.OrderEvents;
import io.github.ffetih.ecommerce.order.application.port.OrderRepository;
import io.github.ffetih.ecommerce.order.application.port.PriceCatalog;
import io.github.ffetih.ecommerce.order.application.port.Transactions;
import io.github.ffetih.ecommerce.order.domain.Money;
import io.github.ffetih.ecommerce.order.domain.Order;
import io.github.ffetih.ecommerce.order.domain.Sku;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Placement rules with in-memory fakes for every port: no Spring, no database, milliseconds to run. */
class OrderServiceTest {

    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final Sku KEYBOARD = Sku.of("KB-01");
    private static final Sku MOUSE = Sku.of("MOUSE-01");

    private final FakeOrders orders = new FakeOrders();
    private final FakeCustomers customers = new FakeCustomers();
    private final FakeCatalog catalog = new FakeCatalog();
    private final List<Order> publishedEvents = new ArrayList<>();
    private final OrderEvents events = new OrderEvents() {
        @Override
        public void orderPlaced(Order order) {
            publishedEvents.add(order);
        }

        @Override
        public void orderConfirmed(Order order) {
            throw new AssertionError("placing an order must not confirm it");
        }

        @Override
        public void cancellationRequested(Order order) {
            publishedEvents.add(order);
        }

        @Override
        public void orderCancelled(Order order) {
            throw new AssertionError("placing an order must not cancel it");
        }
    };
    private final Transactions inline = new Transactions() {
        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return work.get();
        }
    };

    private final OrderService service = new OrderService(orders, customers, catalog, events, inline,
            Clock.fixed(Instant.parse("2026-11-25T10:00:00Z"), ZoneOffset.UTC));

    OrderServiceTest() {
        customers.add(new CustomerDirectory.CustomerSnapshot(CUSTOMER, true, "Bağdat Cd. 1, İstanbul"));
        catalog.add(KEYBOARD, "89.90", true);
        catalog.add(MOUSE, "24.50", true);
    }

    private static PlaceOrderCommand order(int keyboards, int mice) {
        return new PlaceOrderCommand(CUSTOMER,
                List.of(new PlaceOrderCommand.Line(KEYBOARD, keyboards), new PlaceOrderCommand.Line(MOUSE, mice)), null);
    }

    @Test
    void pricesLinesFromTheCatalogAndPublishesOrderPlaced() {
        OrderService.Placement placement = service.place(order(2, 1), UUID.randomUUID());

        assertThat(placement.replayed()).isFalse();
        assertThat(placement.order().total()).isEqualTo(Money.of("204.30", "EUR"));
        assertThat(placement.order().shippingAddress()).isEqualTo("Bağdat Cd. 1, İstanbul");
        assertThat(publishedEvents).containsExactly(placement.order());
    }

    @Test
    void repeatingARequestWithTheSameKeyReturnsTheSameOrderOnce() {
        UUID key = UUID.randomUUID();

        Order first = service.place(order(2, 1), key).order();
        OrderService.Placement again = service.place(order(2, 1), key);

        assertThat(again.replayed()).isTrue();
        assertThat(again.order().id()).isEqualTo(first.id());
        assertThat(publishedEvents).hasSize(1);
    }

    @Test
    void lineOrderDoesNotChangeTheRequestFingerprint() {
        UUID key = UUID.randomUUID();
        service.place(order(2, 1), key);
        var reordered = new PlaceOrderCommand(CUSTOMER,
                List.of(new PlaceOrderCommand.Line(MOUSE, 1), new PlaceOrderCommand.Line(KEYBOARD, 2)), null);

        assertThat(service.place(reordered, key).replayed()).isTrue();
    }

    @Test
    void reusingAKeyForADifferentOrderIsRejected() {
        UUID key = UUID.randomUUID();
        service.place(order(2, 1), key);

        assertThatThrownBy(() -> service.place(order(3, 1), key)).isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @Test
    void aRequestThatLosesTheInsertRaceReturnsTheWinnersOrder() {
        UUID key = UUID.randomUUID();
        Order winner = service.place(order(2, 1), key).order();
        orders.hideNextLookup(); // simulates: our replay check ran before the winner committed

        OrderService.Placement loser = service.place(order(2, 1), key);

        assertThat(loser.replayed()).isTrue();
        assertThat(loser.order().id()).isEqualTo(winner.id());
        assertThat(publishedEvents).hasSize(1);
    }

    @Test
    void rejectsUnknownOrInactiveCustomersAndProducts() {
        UUID inactive = UUID.randomUUID();
        customers.add(new CustomerDirectory.CustomerSnapshot(inactive, false, "Somewhere"));
        catalog.add(Sku.of("OLD-01"), "1.00", false);

        assertThatThrownBy(() -> service.place(new PlaceOrderCommand(UUID.randomUUID(),
                List.of(new PlaceOrderCommand.Line(KEYBOARD, 1)), null), UUID.randomUUID()))
                .isInstanceOf(OrderRejectedException.class).hasMessageContaining("Unknown customer");
        assertThatThrownBy(() -> service.place(new PlaceOrderCommand(inactive,
                List.of(new PlaceOrderCommand.Line(KEYBOARD, 1)), null), UUID.randomUUID()))
                .isInstanceOf(OrderRejectedException.class).hasMessageContaining("deactivated");
        assertThatThrownBy(() -> service.place(new PlaceOrderCommand(CUSTOMER,
                List.of(new PlaceOrderCommand.Line(Sku.of("NOPE-01"), 1)), null), UUID.randomUUID()))
                .isInstanceOf(OrderRejectedException.class).hasMessageContaining("Unknown product");
        assertThatThrownBy(() -> service.place(new PlaceOrderCommand(CUSTOMER,
                List.of(new PlaceOrderCommand.Line(Sku.of("OLD-01"), 1)), null), UUID.randomUUID()))
                .isInstanceOf(OrderRejectedException.class).hasMessageContaining("no longer sold");
        assertThat(publishedEvents).isEmpty();
    }

    @Test
    void customersSeeOnlyTheirOwnOrdersAndTheBackOfficeSeesAll() {
        Order placed = service.place(order(2, 1), UUID.randomUUID()).order();
        Requester owner = Requester.customer(CUSTOMER);
        Requester stranger = Requester.customer(UUID.randomUUID());

        assertThat(service.get(placed.id(), owner).id()).isEqualTo(placed.id());
        assertThat(service.get(placed.id(), Requester.backOffice()).id()).isEqualTo(placed.id());
        // Not "forbidden": for a stranger the order does not exist.
        assertThatThrownBy(() -> service.get(placed.id(), stranger)).isInstanceOf(OrderNotFoundException.class);

        assertThat(service.forCustomer(CUSTOMER, owner)).extracting(Order::id).contains(placed.id());
        assertThat(service.forCustomer(CUSTOMER, Requester.backOffice())).extracting(Order::id).contains(placed.id());
        assertThatThrownBy(() -> service.forCustomer(CUSTOMER, stranger)).isInstanceOf(NotAllowedException.class);
    }

    // --- fakes ---------------------------------------------------------------------------------

    public static final class FakeOrders implements OrderRepository {

        private final Map<UUID, Order> byId = new HashMap<>();
        private final Map<String, StoredOrder> byKey = new HashMap<>();
        private boolean hideNextLookup;

        void hideNextLookup() {
            hideNextLookup = true;
        }

        @Override
        public Optional<Order> findById(UUID orderId) {
            return Optional.ofNullable(byId.get(orderId));
        }

        @Override
        public List<Order> findByCustomer(UUID customerId) {
            return byId.values().stream().filter(o -> o.customerId().equals(customerId)).toList();
        }

        @Override
        public Optional<StoredOrder> findByIdempotencyKey(UUID customerId, UUID idempotencyKey) {
            if (hideNextLookup) {
                hideNextLookup = false;
                return Optional.empty();
            }
            return Optional.ofNullable(byKey.get(customerId + "/" + idempotencyKey));
        }

        @Override
        public void insert(Order order, UUID idempotencyKey, String requestHash) {
            String key = order.customerId() + "/" + idempotencyKey;
            if (byKey.containsKey(key)) {
                throw new DuplicateIdempotencyKeyException(null);
            }
            byKey.put(key, new StoredOrder(order, requestHash));
            byId.put(order.id(), order);
        }

        @Override
        public void update(Order order) {
            byId.put(order.id(), order);
        }
    }

    static final class FakeCustomers implements CustomerDirectory {

        private final Map<UUID, CustomerSnapshot> customers = new HashMap<>();

        void add(CustomerSnapshot customer) {
            customers.put(customer.id(), customer);
        }

        @Override
        public Optional<CustomerSnapshot> find(UUID customerId) {
            return Optional.ofNullable(customers.get(customerId));
        }
    }

    static final class FakeCatalog implements PriceCatalog {

        private final Map<Sku, ProductPrice> prices = new HashMap<>();

        void add(Sku sku, String price, boolean active) {
            prices.put(sku, new ProductPrice(sku, Money.of(price, "EUR"), active));
        }

        @Override
        public Map<Sku, ProductPrice> currentPrices(Collection<Sku> skus) {
            Map<Sku, ProductPrice> found = new HashMap<>();
            skus.forEach(sku -> {
                if (prices.containsKey(sku)) {
                    found.put(sku, prices.get(sku));
                }
            });
            return found;
        }
    }
}

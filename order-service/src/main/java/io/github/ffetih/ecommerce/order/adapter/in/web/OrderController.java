package io.github.ffetih.ecommerce.order.adapter.in.web;

import io.github.ffetih.ecommerce.order.application.OrderService;
import io.github.ffetih.ecommerce.order.application.PlaceOrderCommand;
import io.github.ffetih.ecommerce.order.domain.Order;
import io.github.ffetih.ecommerce.order.domain.Sku;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
class OrderController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final OrderService orders;

    OrderController(OrderService orders) {
        this.orders = orders;
    }

    /**
     * Places an order. The client generates a UUID per order attempt and sends it as
     * {@code Idempotency-Key}; repeating the request (e.g. after a timeout) returns the same order
     * with {@code 200} and {@code Idempotent-Replayed: true} instead of creating a second one.
     */
    @PostMapping
    ResponseEntity<OrderResponse> place(@RequestHeader(IDEMPOTENCY_KEY) UUID idempotencyKey,
                                        @Valid @RequestBody PlaceOrderRequest request) {
        var command = new PlaceOrderCommand(
                request.customerId(),
                request.lines().stream().map(line -> new PlaceOrderCommand.Line(Sku.of(line.sku()), line.quantity())).toList(),
                request.shippingAddress());
        OrderService.Placement placement = orders.place(command, idempotencyKey);
        OrderResponse body = OrderResponse.from(placement.order());
        if (placement.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(body);
        }
        return ResponseEntity.created(URI.create("/api/v1/orders/" + body.id())).body(body);
    }

    @GetMapping("/{orderId}")
    OrderResponse get(@PathVariable UUID orderId) {
        return OrderResponse.from(orders.get(orderId));
    }

    @GetMapping
    List<OrderResponse> forCustomer(@RequestParam UUID customerId) {
        return orders.forCustomer(customerId).stream().map(OrderResponse::from).toList();
    }

    record PlaceOrderRequest(
            @NotNull UUID customerId,
            @NotEmpty @Size(max = Order.MAX_LINES) List<@Valid LineRequest> lines,
            String shippingAddress) {
    }

    record LineRequest(@NotBlank String sku, @Positive int quantity) {
    }

    record LineResponse(String sku, int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {
    }

    record OrderResponse(
            UUID id,
            UUID customerId,
            Order.Status status,
            List<LineResponse> lines,
            BigDecimal totalAmount,
            String currency,
            String shippingAddress,
            String cancellationReason,
            Instant createdAt) {

        static OrderResponse from(Order order) {
            return new OrderResponse(
                    order.id(),
                    order.customerId(),
                    order.status(),
                    order.lines().stream()
                            .map(line -> new LineResponse(line.sku().value(), line.quantity(),
                                    line.unitPrice().amount(), line.lineTotal().amount()))
                            .toList(),
                    order.total().amount(),
                    order.total().currency().getCurrencyCode(),
                    order.shippingAddress(),
                    order.cancellationReason(),
                    order.createdAt());
        }
    }
}

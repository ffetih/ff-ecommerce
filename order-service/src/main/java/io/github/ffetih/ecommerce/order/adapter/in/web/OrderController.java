package io.github.ffetih.ecommerce.order.adapter.in.web;

import io.github.ffetih.ecommerce.order.application.OrderSagaService;
import io.github.ffetih.ecommerce.order.application.OrderService;
import io.github.ffetih.ecommerce.order.application.PlaceOrderCommand;
import io.github.ffetih.ecommerce.order.application.Requester;
import io.github.ffetih.ecommerce.order.domain.Order;
import io.github.ffetih.ecommerce.order.domain.Sku;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
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

/**
 * Orders. The customer is always the caller: the access token's {@code sub}. A request body that
 * names another customer has no field for it, so it cannot place or read orders for someone else.
 */
@RestController
@RequestMapping("/api/v1/orders")
class OrderController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final OrderService orders;
    private final OrderSagaService saga;

    OrderController(OrderService orders, OrderSagaService saga) {
        this.orders = orders;
        this.saga = saga;
    }

    /**
     * Places an order for the caller. The client generates a UUID per order attempt and sends it as
     * {@code Idempotency-Key}; repeating the request (e.g. after a timeout) returns the same order
     * with {@code 200} and {@code Idempotent-Replayed: true} instead of creating a second one.
     */
    @PostMapping
    ResponseEntity<OrderResponse> place(JwtAuthenticationToken caller,
                                        @RequestHeader(IDEMPOTENCY_KEY) UUID idempotencyKey,
                                        @Valid @RequestBody PlaceOrderRequest request) {
        var command = new PlaceOrderCommand(
                Requesters.customerId(caller),
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
    OrderResponse get(JwtAuthenticationToken caller, @PathVariable UUID orderId) {
        return OrderResponse.from(orders.get(orderId, Requesters.of(caller)));
    }

    /** The caller's orders; the back office may ask for any customer's with {@code ?customerId=}. */
    @GetMapping
    List<OrderResponse> list(JwtAuthenticationToken caller, @RequestParam(required = false) UUID customerId) {
        UUID whose = customerId != null ? customerId : Requesters.customerId(caller);
        return orders.forCustomer(whose, Requesters.of(caller)).stream().map(OrderResponse::from).toList();
    }

    /** Possible until the parcel is dispatched; afterwards {@code 409}. Repeating it changes nothing. */
    @PostMapping("/{orderId}/cancel")
    OrderResponse cancel(JwtAuthenticationToken caller, @PathVariable UUID orderId,
                         @Valid @RequestBody CancelRequest request) {
        return OrderResponse.from(saga.cancelByCustomer(orderId, request.reason(), Requesters.of(caller)));
    }

    record CancelRequest(@NotBlank @Size(max = 500) String reason) {
    }

    record PlaceOrderRequest(
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

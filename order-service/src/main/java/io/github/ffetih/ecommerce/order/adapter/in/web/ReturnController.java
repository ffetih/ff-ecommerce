package io.github.ffetih.ecommerce.order.adapter.in.web;

import io.github.ffetih.ecommerce.order.application.ReturnService;
import io.github.ffetih.ecommerce.order.domain.Return;
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
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Returns of delivered orders (ADR-0010). Customers request them for their own orders; the back
 * office receives the parcel (which starts the refund) or rejects the return.
 */
@RestController
class ReturnController {

    private final ReturnService returns;

    ReturnController(ReturnService returns) {
        this.returns = returns;
    }

    /**
     * Asks to send goods back. The client generates a UUID per return attempt and sends it as
     * {@code Idempotency-Key}; repeating the request returns the same return with {@code 200}.
     * {@code 422} if the window has passed, the order is not paid, or the lines would exceed what was bought.
     */
    @PostMapping("/api/v1/orders/{orderId}/returns")
    ResponseEntity<ReturnResponse> request(JwtAuthenticationToken caller, @PathVariable UUID orderId,
                                           @RequestHeader(OrderController.IDEMPOTENCY_KEY) UUID idempotencyKey,
                                           @Valid @RequestBody ReturnRequest request) {
        ReturnService.Placement placement = returns.request(orderId,
                request.lines().stream().map(line -> new Return.Requested(Sku.of(line.sku()), line.quantity())).toList(),
                request.reason(), Requesters.of(caller), idempotencyKey);
        ReturnResponse body = ReturnResponse.from(placement.value());
        if (placement.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(body);
        }
        return ResponseEntity.created(URI.create("/api/v1/returns/" + body.id())).body(body);
    }

    /** The order's returns, and how many units of each SKU they hold (rejected returns excluded). */
    @GetMapping("/api/v1/orders/{orderId}/returns")
    OrderReturnsResponse forOrder(JwtAuthenticationToken caller, @PathVariable UUID orderId) {
        ReturnService.OrderReturns found = returns.forOrder(orderId, Requesters.of(caller));
        Map<String, Integer> returned = new TreeMap<>();
        found.returnedUnits().forEach((sku, units) -> returned.put(sku.value(), units));
        return new OrderReturnsResponse(found.returns().stream().map(ReturnResponse::from).toList(), returned);
    }

    @GetMapping("/api/v1/returns/{returnId}")
    ReturnResponse get(JwtAuthenticationToken caller, @PathVariable UUID returnId) {
        return ReturnResponse.from(returns.get(returnId, Requesters.of(caller)));
    }

    /** The warehouse has the parcel. Repeating it changes nothing. */
    @PostMapping("/api/v1/returns/{returnId}/receive")
    ReturnResponse receive(@PathVariable UUID returnId) {
        return ReturnResponse.from(returns.receive(returnId));
    }

    @PostMapping("/api/v1/returns/{returnId}/reject")
    ReturnResponse reject(@PathVariable UUID returnId, @Valid @RequestBody RejectRequest request) {
        return ReturnResponse.from(returns.reject(returnId, request.reason()));
    }

    record ReturnRequest(
            @NotEmpty @Size(max = 50) List<@Valid LineRequest> lines,
            @NotBlank @Size(max = Return.MAX_REASON_LENGTH) String reason) {
    }

    record LineRequest(@NotBlank String sku, @Positive int quantity) {
    }

    record RejectRequest(@NotBlank @Size(max = Return.MAX_REASON_LENGTH) String reason) {
    }

    record LineResponse(String sku, int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {
    }

    record ReturnResponse(
            UUID id,
            UUID orderId,
            UUID customerId,
            Return.Status status,
            List<LineResponse> lines,
            BigDecimal refundAmount,
            String currency,
            String reason,
            String rejectionReason,
            Instant createdAt) {

        static ReturnResponse from(Return value) {
            return new ReturnResponse(
                    value.id(),
                    value.orderId(),
                    value.customerId(),
                    value.status(),
                    value.lines().stream()
                            .map(line -> new LineResponse(line.sku().value(), line.quantity(),
                                    line.unitPrice().amount(), line.lineTotal().amount()))
                            .toList(),
                    value.refundAmount().amount(),
                    value.refundAmount().currency().getCurrencyCode(),
                    value.reason(),
                    value.rejectionReason(),
                    value.createdAt());
        }
    }

    record OrderReturnsResponse(List<ReturnResponse> returns, Map<String, Integer> returnedUnits) {
    }
}

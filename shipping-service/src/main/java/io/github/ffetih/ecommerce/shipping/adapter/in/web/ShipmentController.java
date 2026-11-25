package io.github.ffetih.ecommerce.shipping.adapter.in.web;

import io.github.ffetih.ecommerce.shipping.application.ShippingService;
import io.github.ffetih.ecommerce.shipping.domain.Shipment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Warehouse and carrier operations. Operator-only once authentication arrives in s05. */
@RestController
@RequestMapping("/api/v1/shipments")
class ShipmentController {

    private final ShippingService shipping;

    ShipmentController(ShippingService shipping) {
        this.shipping = shipping;
    }

    @GetMapping("/{shipmentId}")
    ShipmentResponse get(@PathVariable UUID shipmentId) {
        return ShipmentResponse.from(shipping.get(shipmentId));
    }

    @GetMapping
    ShipmentResponse forOrder(@RequestParam UUID orderId) {
        return ShipmentResponse.from(shipping.forOrder(orderId));
    }

    @PostMapping("/{shipmentId}/dispatch")
    ShipmentResponse dispatch(@PathVariable UUID shipmentId) {
        return ShipmentResponse.from(shipping.dispatch(shipmentId));
    }

    @PostMapping("/{shipmentId}/deliver")
    ShipmentResponse deliver(@PathVariable UUID shipmentId) {
        return ShipmentResponse.from(shipping.deliver(shipmentId));
    }

    record LineResponse(String sku, int quantity) {
    }

    record ShipmentResponse(UUID id, UUID orderId, Shipment.Status status, String trackingNumber,
                            String shippingAddress, List<LineResponse> lines, Instant updatedAt) {

        static ShipmentResponse from(Shipment shipment) {
            return new ShipmentResponse(shipment.id(), shipment.orderId(), shipment.status(), shipment.trackingNumber(),
                    shipment.shippingAddress(),
                    shipment.lines().stream().map(line -> new LineResponse(line.sku(), line.quantity())).toList(),
                    shipment.updatedAt());
        }
    }
}

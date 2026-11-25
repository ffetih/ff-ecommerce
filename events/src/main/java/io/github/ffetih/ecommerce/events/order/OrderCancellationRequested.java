package io.github.ffetih.ecommerce.events.order;

import java.util.UUID;

/**
 * The customer asked to cancel a confirmed order. Not yet a cancellation: shipping decides, because
 * only shipping knows whether the parcel has left (ADR-0007). It answers with
 * {@code shipping.shipment-cancelled} or {@code shipping.cancellation-refused}.
 * Topic {@value OrderPlaced#TOPIC}, key: order id.
 */
public record OrderCancellationRequested(UUID orderId, String reason) {

    public static final String TYPE = "order.cancellation-requested";
    public static final int VERSION = 1;
}

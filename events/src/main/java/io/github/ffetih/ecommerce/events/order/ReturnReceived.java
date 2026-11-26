package io.github.ffetih.ecommerce.events.order;

import java.util.List;
import java.util.UUID;

/**
 * The warehouse has received a returned parcel (ADR-0010): its units are stock again. catalog-service
 * adds them to the stock on hand, once per return. Topic {@value OrderPlaced#TOPIC}, key: order id.
 */
public record ReturnReceived(UUID returnId, UUID orderId, List<Line> lines) {

    public static final String TYPE = "order.return-received";
    public static final int VERSION = 1;

    public record Line(String sku, int quantity) {
    }
}

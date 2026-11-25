package io.github.ffetih.ecommerce.events.order;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A customer placed an order; stock has not been reserved yet. Published on {@value #TOPIC} with the
 * order id as record key, so all lifecycle events of one order arrive in order.
 *
 * @param lines           products with the unit price copied from the catalog at placement time
 * @param totalAmount     sum of all lines, in {@code currency}
 * @param shippingAddress where the order goes, copied from the customer or given for this order
 */
public record OrderPlaced(
        UUID orderId,
        UUID customerId,
        List<Line> lines,
        BigDecimal totalAmount,
        String currency,
        String shippingAddress) {

    public static final String TOPIC = "order.lifecycle";
    public static final String TYPE = "order.order-placed";
    public static final int VERSION = 1;

    public record Line(String sku, int quantity, BigDecimal unitPrice) {
    }
}

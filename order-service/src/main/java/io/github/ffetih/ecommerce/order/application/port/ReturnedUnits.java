package io.github.ffetih.ecommerce.order.application.port;

import io.github.ffetih.ecommerce.order.domain.Sku;

import java.util.Map;
import java.util.UUID;

/**
 * How many units of each order line are returned, counted by the database so that concurrent returns
 * can never together exceed what was ordered (ADR-0010). Call inside the transaction that stores the
 * return, so the count and the return commit or roll back together.
 */
public interface ReturnedUnits {

    /**
     * Counts {@code quantity} more returned units of the line, in one conditional statement.
     *
     * @return {@code false}, changing nothing, if that would exceed {@code ordered}
     */
    boolean take(UUID orderId, Sku sku, int ordered, int quantity);

    /** A rejected return's units can be returned again. */
    void giveBack(UUID orderId, Sku sku, int quantity);

    /** Returned units per SKU; lines without returns are absent. */
    Map<Sku, Integer> of(UUID orderId);
}

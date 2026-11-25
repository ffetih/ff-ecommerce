package io.github.ffetih.ecommerce.catalog.application.port;

import io.github.ffetih.ecommerce.catalog.domain.StockItem;

import java.util.UUID;

/** Announces stock changes. Implementations must record the event in the caller's transaction. */
public interface StockEvents {

    void stockChanged(StockItem item, String reason, UUID reference);
}

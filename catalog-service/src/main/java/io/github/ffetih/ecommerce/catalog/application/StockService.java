package io.github.ffetih.ecommerce.catalog.application;

import io.github.ffetih.ecommerce.catalog.application.port.StockEvents;
import io.github.ffetih.ecommerce.catalog.application.port.StockReceiptRepository;
import io.github.ffetih.ecommerce.catalog.application.port.StockRepository;
import io.github.ffetih.ecommerce.catalog.application.port.Transactions;
import io.github.ffetih.ecommerce.catalog.domain.Sku;
import io.github.ffetih.ecommerce.catalog.domain.StockItem;
import io.github.ffetih.ecommerce.catalog.domain.StockReceipt;
import io.github.ffetih.ecommerce.events.catalog.StockChanged;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class StockService {

    private final StockRepository stock;
    private final StockReceiptRepository receipts;
    private final StockEvents events;
    private final Transactions transactions;
    private final Clock clock;

    public StockService(StockRepository stock, StockReceiptRepository receipts, StockEvents events,
                        Transactions transactions, Clock clock) {
        this.stock = stock;
        this.receipts = receipts;
        this.events = events;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Books a goods receipt exactly once per {@code referenceId}.
     * The stock row is locked first, so a concurrent retry with the same reference waits and then
     * sees the first receipt instead of adding the quantity a second time.
     */
    public StockItem receive(UUID referenceId, Sku sku, int quantity) {
        return transactions.inTransaction(() -> {
            StockItem current = stock.lockAll(List.of(sku)).get(sku);
            if (current == null) {
                throw NotFoundException.product(sku);
            }
            Optional<StockReceipt> previous = receipts.findById(referenceId);
            if (previous.isPresent()) {
                if (!previous.get().isSameRequestAs(sku, quantity)) {
                    throw ConflictException.idempotencyKeyReused(referenceId);
                }
                return current;
            }
            StockItem updated = current.receive(quantity);
            stock.save(updated);
            receipts.save(new StockReceipt(referenceId, sku, quantity, clock.instant()));
            events.stockChanged(updated, StockChanged.RECEIVED, referenceId);
            return updated;
        });
    }

    public StockItem get(Sku sku) {
        return stock.findBySku(sku).orElseThrow(() -> NotFoundException.product(sku));
    }
}

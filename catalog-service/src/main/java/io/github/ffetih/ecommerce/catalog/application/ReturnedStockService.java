package io.github.ffetih.ecommerce.catalog.application;

import io.github.ffetih.ecommerce.catalog.application.port.ProcessedEvents;
import io.github.ffetih.ecommerce.catalog.application.port.StockEvents;
import io.github.ffetih.ecommerce.catalog.application.port.StockReceiptRepository;
import io.github.ffetih.ecommerce.catalog.application.port.StockRepository;
import io.github.ffetih.ecommerce.catalog.application.port.Transactions;
import io.github.ffetih.ecommerce.catalog.domain.Sku;
import io.github.ffetih.ecommerce.catalog.domain.StockItem;
import io.github.ffetih.ecommerce.catalog.domain.StockReceipt;
import io.github.ffetih.ecommerce.events.catalog.StockChanged;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Units of a received return go back into stock (ADR-0010), once.
 * <p>
 * Two guards: the inbox skips the same event delivered twice, and each line is booked as a goods
 * receipt whose reference is derived from the return and the SKU. A receipt is never booked twice,
 * so even the same return announced again with a new event id, or after the inbox has forgotten it,
 * adds nothing.
 */
public class ReturnedStockService {

    /** One line of a returned parcel. */
    public record Line(Sku sku, int quantity) {
    }

    private final StockRepository stock;
    private final StockReceiptRepository receipts;
    private final StockEvents events;
    private final ProcessedEvents processedEvents;
    private final Transactions transactions;
    private final Clock clock;

    public ReturnedStockService(StockRepository stock, StockReceiptRepository receipts, StockEvents events,
                                ProcessedEvents processedEvents, Transactions transactions, Clock clock) {
        this.stock = stock;
        this.receipts = receipts;
        this.events = events;
        this.processedEvents = processedEvents;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** {@code order.return-received}: the returned units are on hand again. */
    public void returnReceived(UUID eventId, UUID returnId, List<Line> lines) {
        transactions.inTransaction(() -> {
            if (!processedEvents.firstDelivery(ReservationService.ORDER_EVENTS_CONSUMER, eventId)) {
                return null;
            }
            Map<Sku, StockItem> locked = stock.lockAll(lines.stream().map(Line::sku).toList());
            Instant now = clock.instant();
            for (Line line : lines) {
                UUID receipt = receiptId(returnId, line.sku());
                if (receipts.findById(receipt).isPresent()) {
                    continue; // this return's units are already back
                }
                StockItem current = locked.get(line.sku());
                if (current == null) {
                    throw NotFoundException.product(line.sku());
                }
                StockItem restocked = current.receive(line.quantity());
                stock.save(restocked);
                receipts.save(new StockReceipt(receipt, line.sku(), line.quantity(), now));
                events.stockChanged(restocked, StockChanged.RETURNED, returnId);
            }
            return null;
        });
    }

    /** The same for every delivery of the same return, different for each of its lines. */
    static UUID receiptId(UUID returnId, Sku sku) {
        return UUID.nameUUIDFromBytes(("return:" + returnId + ":" + sku.value()).getBytes(StandardCharsets.UTF_8));
    }
}

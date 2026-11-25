package io.github.ffetih.ecommerce.catalog.adapter.in.web;

import io.github.ffetih.ecommerce.catalog.application.StockService;
import io.github.ffetih.ecommerce.catalog.domain.Sku;
import io.github.ffetih.ecommerce.catalog.domain.StockItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/stock")
class StockController {

    private final StockService stock;

    StockController(StockService stock) {
        this.stock = stock;
    }

    @GetMapping("/{sku}")
    StockResponse get(@PathVariable String sku) {
        return StockResponse.from(stock.get(Sku.of(sku)));
    }

    /** Goods receipt. Retrying with the same {@code referenceId} is safe: the quantity is added once. */
    @PostMapping("/{sku}/receipts")
    StockResponse receive(@PathVariable String sku, @Valid @RequestBody ReceiptRequest request) {
        return StockResponse.from(stock.receive(request.referenceId(), Sku.of(sku), request.quantity()));
    }

    record ReceiptRequest(@NotNull UUID referenceId, @Positive int quantity) {
    }

    record StockResponse(String sku, int onHand, int reserved, int available) {

        static StockResponse from(StockItem item) {
            return new StockResponse(item.sku().value(), item.onHand(), item.reserved(), item.available());
        }
    }
}

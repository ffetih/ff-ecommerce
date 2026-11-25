package io.github.ffetih.ecommerce.catalog.application.port;

import io.github.ffetih.ecommerce.catalog.domain.StockReceipt;

import java.util.Optional;
import java.util.UUID;

public interface StockReceiptRepository {

    Optional<StockReceipt> findById(UUID referenceId);

    void save(StockReceipt receipt);
}

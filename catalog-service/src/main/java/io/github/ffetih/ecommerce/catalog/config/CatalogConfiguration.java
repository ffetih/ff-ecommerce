package io.github.ffetih.ecommerce.catalog.config;

import io.github.ffetih.ecommerce.catalog.application.ProductService;
import io.github.ffetih.ecommerce.catalog.application.ReservationService;
import io.github.ffetih.ecommerce.catalog.application.StockService;
import io.github.ffetih.ecommerce.catalog.application.port.ProductRepository;
import io.github.ffetih.ecommerce.catalog.application.port.ReservationRepository;
import io.github.ffetih.ecommerce.catalog.application.port.StockReceiptRepository;
import io.github.ffetih.ecommerce.catalog.application.port.StockRepository;
import io.github.ffetih.ecommerce.catalog.application.port.Transactions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Wires the framework-free application services to their adapters. */
@Configuration(proxyBeanMethods = false)
class CatalogConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ProductService productService(ProductRepository products, StockRepository stock, Transactions transactions) {
        return new ProductService(products, stock, transactions);
    }

    @Bean
    StockService stockService(StockRepository stock, StockReceiptRepository receipts,
                              Transactions transactions, Clock clock) {
        return new StockService(stock, receipts, transactions, clock);
    }

    @Bean
    ReservationService reservationService(StockRepository stock, ReservationRepository reservations,
                                          Transactions transactions, Clock clock) {
        return new ReservationService(stock, reservations, transactions, clock);
    }
}

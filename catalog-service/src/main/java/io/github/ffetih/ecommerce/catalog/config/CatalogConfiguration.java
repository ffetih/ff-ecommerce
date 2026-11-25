package io.github.ffetih.ecommerce.catalog.config;

import io.github.ffetih.ecommerce.catalog.application.ProductService;
import io.github.ffetih.ecommerce.catalog.application.ReservationService;
import io.github.ffetih.ecommerce.catalog.application.StockService;
import io.github.ffetih.ecommerce.catalog.application.port.ProductRepository;
import io.github.ffetih.ecommerce.catalog.application.port.ReservationRepository;
import io.github.ffetih.ecommerce.catalog.application.port.StockEvents;
import io.github.ffetih.ecommerce.catalog.application.port.StockReceiptRepository;
import io.github.ffetih.ecommerce.catalog.application.port.StockRepository;
import io.github.ffetih.ecommerce.catalog.application.port.Transactions;
import org.springframework.context.annotation.Bean;
import io.github.ffetih.ecommerce.events.catalog.StockChanged;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

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
    StockService stockService(StockRepository stock, StockReceiptRepository receipts, StockEvents events,
                              Transactions transactions, Clock clock) {
        return new StockService(stock, receipts, events, transactions, clock);
    }

    @Bean
    ReservationService reservationService(StockRepository stock, ReservationRepository reservations,
                                          StockEvents events, Transactions transactions, Clock clock) {
        return new ReservationService(stock, reservations, events, transactions, clock);
    }

    /** Created at startup if missing. Three partitions allow three consumers per group to share the SKUs. */
    @Bean
    NewTopic stockTopic() {
        return TopicBuilder.name(StockChanged.TOPIC).partitions(3).replicas(1).build();
    }
}

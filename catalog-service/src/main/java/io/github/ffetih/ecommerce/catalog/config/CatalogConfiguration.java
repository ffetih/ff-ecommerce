package io.github.ffetih.ecommerce.catalog.config;

import io.github.ffetih.ecommerce.catalog.application.ProductService;
import io.github.ffetih.ecommerce.catalog.application.ReservationService;
import io.github.ffetih.ecommerce.catalog.application.StockService;
import io.github.ffetih.ecommerce.catalog.application.port.ProcessedEvents;
import io.github.ffetih.ecommerce.catalog.application.port.ProductRepository;
import io.github.ffetih.ecommerce.catalog.application.port.ReservationEvents;
import io.github.ffetih.ecommerce.catalog.application.port.ReservationRepository;
import io.github.ffetih.ecommerce.catalog.application.port.StockEvents;
import io.github.ffetih.ecommerce.catalog.application.port.StockReceiptRepository;
import io.github.ffetih.ecommerce.catalog.application.port.StockRepository;
import io.github.ffetih.ecommerce.catalog.application.port.Transactions;
import io.github.ffetih.ecommerce.events.Topics;
import io.github.ffetih.ecommerce.events.catalog.StockChanged;
import io.github.ffetih.ecommerce.events.catalog.StockReserved;
import io.github.ffetih.ecommerce.events.order.OrderPlaced;
import io.github.ffetih.ecommerce.events.shipping.ShipmentDispatched;
import io.github.ffetih.ecommerce.messaging.inbox.DeadLetters;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

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
                                          StockEvents stockEvents, ReservationEvents reservationEvents,
                                          ProcessedEvents processedEvents, Transactions transactions, Clock clock) {
        return new ReservationService(stock, reservations, stockEvents, reservationEvents, processedEvents,
                transactions, clock);
    }

    /**
     * Topics catalog writes to, reads from, and the dead-letter topics of the ones it reads. Creating
     * them is idempotent, so every service declares what it needs and startup order does not matter.
     */
    @Bean
    KafkaAdmin.NewTopics catalogTopics() {
        return new KafkaAdmin.NewTopics(
                topic(StockChanged.TOPIC),
                topic(StockReserved.TOPIC),
                topic(OrderPlaced.TOPIC),
                topic(DeadLetters.topicFor(OrderPlaced.TOPIC)),
                topic(ShipmentDispatched.TOPIC),
                topic(DeadLetters.topicFor(ShipmentDispatched.TOPIC)));
    }

    private static org.apache.kafka.clients.admin.NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(Topics.PARTITIONS).replicas(1).build();
    }
}

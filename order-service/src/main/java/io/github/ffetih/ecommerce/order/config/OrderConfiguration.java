package io.github.ffetih.ecommerce.order.config;

import io.github.ffetih.ecommerce.events.Topics;
import io.github.ffetih.ecommerce.events.catalog.StockReserved;
import io.github.ffetih.ecommerce.events.order.OrderPlaced;
import io.github.ffetih.ecommerce.events.shipping.ShipmentDispatched;
import io.github.ffetih.ecommerce.messaging.inbox.DeadLetters;
import io.github.ffetih.ecommerce.order.application.OrderExpiry;
import io.github.ffetih.ecommerce.order.application.OrderSagaService;
import io.github.ffetih.ecommerce.order.application.OrderService;
import io.github.ffetih.ecommerce.order.application.port.CustomerDirectory;
import io.github.ffetih.ecommerce.order.application.port.OrderEvents;
import io.github.ffetih.ecommerce.order.application.port.OrderRepository;
import io.github.ffetih.ecommerce.order.application.port.PriceCatalog;
import io.github.ffetih.ecommerce.order.application.port.ProcessedEvents;
import io.github.ffetih.ecommerce.order.application.port.Transactions;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ClientProperties.class, ExpiryProperties.class})
class OrderConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    OrderService orderService(OrderRepository orders, CustomerDirectory customers, PriceCatalog catalog,
                              OrderEvents events, Transactions transactions, Clock clock) {
        return new OrderService(orders, customers, catalog, events, transactions, clock);
    }

    @Bean
    OrderSagaService orderSagaService(OrderRepository orders, OrderEvents events, ProcessedEvents processedEvents,
                                      Transactions transactions, Clock clock) {
        return new OrderSagaService(orders, events, processedEvents, transactions, clock);
    }

    @Bean
    OrderExpiry orderExpiry(OrderRepository orders, OrderEvents events, Transactions transactions,
                            ExpiryProperties properties) {
        return new OrderExpiry(orders, events, transactions, new OrderExpiry.Settings(
                properties.placedAfter(), properties.confirmedAfter(), properties.batchSize()));
    }

    /** Topics order-service writes, reads, and the dead-letter topics of those it reads (idempotent creation). */
    @Bean
    KafkaAdmin.NewTopics orderTopics() {
        return new KafkaAdmin.NewTopics(
                topic(OrderPlaced.TOPIC),
                topic(StockReserved.TOPIC),
                topic(DeadLetters.topicFor(StockReserved.TOPIC)),
                topic(ShipmentDispatched.TOPIC),
                topic(DeadLetters.topicFor(ShipmentDispatched.TOPIC)));
    }

    private static NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(Topics.PARTITIONS).replicas(1).build();
    }
}

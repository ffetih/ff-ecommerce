package io.github.ffetih.ecommerce.order.config;

import io.github.ffetih.ecommerce.events.Topics;
import io.github.ffetih.ecommerce.events.catalog.StockReserved;
import io.github.ffetih.ecommerce.events.order.OrderPlaced;
import io.github.ffetih.ecommerce.events.payment.AuthorizePayment;
import io.github.ffetih.ecommerce.events.payment.PaymentAuthorized;
import io.github.ffetih.ecommerce.events.shipping.ShipmentDispatched;
import io.github.ffetih.ecommerce.messaging.MessagingTopics;
import io.github.ffetih.ecommerce.order.application.OrderExpiry;
import io.github.ffetih.ecommerce.order.application.OrderSagaService;
import io.github.ffetih.ecommerce.order.application.OrderService;
import io.github.ffetih.ecommerce.order.application.PaymentSteps;
import io.github.ffetih.ecommerce.order.application.ReturnService;
import io.github.ffetih.ecommerce.order.application.port.CustomerDirectory;
import io.github.ffetih.ecommerce.order.application.port.OrderEvents;
import io.github.ffetih.ecommerce.order.application.port.OrderRepository;
import io.github.ffetih.ecommerce.order.application.port.PaymentCommands;
import io.github.ffetih.ecommerce.order.application.port.PriceCatalog;
import io.github.ffetih.ecommerce.order.application.port.ProcessedEvents;
import io.github.ffetih.ecommerce.order.application.port.ReturnEvents;
import io.github.ffetih.ecommerce.order.application.port.ReturnRepository;
import io.github.ffetih.ecommerce.order.application.port.ReturnedUnits;
import io.github.ffetih.ecommerce.order.application.port.Transactions;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

import java.time.Clock;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ClientProperties.class, ExpiryProperties.class, ReturnProperties.class})
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
    ReturnService returnService(OrderRepository orders, ReturnRepository returns, ReturnedUnits returnedUnits,
                                ReturnEvents events, PaymentCommands payments, ProcessedEvents processedEvents,
                                Transactions transactions, Clock clock, ReturnProperties properties) {
        return new ReturnService(orders, returns, returnedUnits, events, payments, processedEvents, transactions, clock,
                new ReturnService.Settings(properties.window()));
    }

    @Bean
    PaymentSteps paymentSteps(PaymentCommands commands) {
        return new PaymentSteps(commands);
    }

    @Bean
    OrderSagaService orderSagaService(OrderRepository orders, OrderEvents events, PaymentSteps payments,
                                      ProcessedEvents processedEvents, Transactions transactions, Clock clock) {
        return new OrderSagaService(orders, events, payments, processedEvents, transactions, clock);
    }

    @Bean
    OrderExpiry orderExpiry(OrderRepository orders, OrderEvents events, PaymentSteps payments, Transactions transactions,
                            ExpiryProperties properties) {
        return new OrderExpiry(orders, events, payments, transactions, new OrderExpiry.Settings(
                properties.placedAfter(), properties.confirmedAfter(), properties.batchSize()));
    }

    /** Topics order-service writes, reads, and the dead-letter topics of those it reads (idempotent creation). */
    @Bean
    KafkaAdmin.NewTopics orderTopics(MessagingTopics topics) {
        return topics.declare(Topics.PARTITIONS,
                List.of(OrderPlaced.TOPIC, AuthorizePayment.TOPIC),
                List.of(StockReserved.TOPIC, ShipmentDispatched.TOPIC, PaymentAuthorized.TOPIC));
    }
}

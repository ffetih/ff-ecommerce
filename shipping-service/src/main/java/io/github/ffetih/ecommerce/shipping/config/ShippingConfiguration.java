package io.github.ffetih.ecommerce.shipping.config;

import io.github.ffetih.ecommerce.events.Topics;
import io.github.ffetih.ecommerce.events.order.OrderPlaced;
import io.github.ffetih.ecommerce.events.shipping.ShipmentDispatched;
import io.github.ffetih.ecommerce.messaging.MessagingTopics;
import io.github.ffetih.ecommerce.shipping.application.ShippingService;
import io.github.ffetih.ecommerce.shipping.application.port.CancelledOrders;
import io.github.ffetih.ecommerce.shipping.application.port.ProcessedEvents;
import io.github.ffetih.ecommerce.shipping.application.port.ShipmentEvents;
import io.github.ffetih.ecommerce.shipping.application.port.ShipmentRepository;
import io.github.ffetih.ecommerce.shipping.application.port.Transactions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

import java.time.Clock;
import java.util.List;

@Configuration(proxyBeanMethods = false)
class ShippingConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ShippingService shippingService(ShipmentRepository shipments, ShipmentEvents events, ProcessedEvents processedEvents,
                                    CancelledOrders cancelledOrders, Transactions transactions, Clock clock) {
        return new ShippingService(shipments, events, processedEvents, cancelledOrders, transactions, clock);
    }

    @Bean
    KafkaAdmin.NewTopics shippingTopics(MessagingTopics topics) {
        return topics.declare(Topics.PARTITIONS,
                List.of(ShipmentDispatched.TOPIC),
                List.of(OrderPlaced.TOPIC));
    }
}

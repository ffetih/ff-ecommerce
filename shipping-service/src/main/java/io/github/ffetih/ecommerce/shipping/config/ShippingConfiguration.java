package io.github.ffetih.ecommerce.shipping.config;

import io.github.ffetih.ecommerce.events.Topics;
import io.github.ffetih.ecommerce.events.order.OrderPlaced;
import io.github.ffetih.ecommerce.events.shipping.ShipmentDispatched;
import io.github.ffetih.ecommerce.messaging.inbox.DeadLetters;
import io.github.ffetih.ecommerce.shipping.application.ShippingService;
import io.github.ffetih.ecommerce.shipping.application.port.ProcessedEvents;
import io.github.ffetih.ecommerce.shipping.application.port.ShipmentEvents;
import io.github.ffetih.ecommerce.shipping.application.port.ShipmentRepository;
import io.github.ffetih.ecommerce.shipping.application.port.Transactions;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
class ShippingConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ShippingService shippingService(ShipmentRepository shipments, ShipmentEvents events, ProcessedEvents processedEvents,
                                    Transactions transactions, Clock clock) {
        return new ShippingService(shipments, events, processedEvents, transactions, clock);
    }

    @Bean
    KafkaAdmin.NewTopics shippingTopics() {
        return new KafkaAdmin.NewTopics(
                topic(ShipmentDispatched.TOPIC),
                topic(OrderPlaced.TOPIC),
                topic(DeadLetters.topicFor(OrderPlaced.TOPIC)));
    }

    private static NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(Topics.PARTITIONS).replicas(1).build();
    }
}

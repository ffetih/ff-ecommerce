package io.github.ffetih.ecommerce.order.config;

import io.github.ffetih.ecommerce.events.order.OrderPlaced;
import io.github.ffetih.ecommerce.order.application.OrderService;
import io.github.ffetih.ecommerce.order.application.port.CustomerDirectory;
import io.github.ffetih.ecommerce.order.application.port.OrderEvents;
import io.github.ffetih.ecommerce.order.application.port.OrderRepository;
import io.github.ffetih.ecommerce.order.application.port.PriceCatalog;
import io.github.ffetih.ecommerce.order.application.port.Transactions;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ClientProperties.class)
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
    NewTopic orderLifecycleTopic() {
        return TopicBuilder.name(OrderPlaced.TOPIC).partitions(3).replicas(1).build();
    }
}

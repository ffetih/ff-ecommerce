package io.github.ffetih.ecommerce.payment.config;

import io.github.ffetih.ecommerce.events.Topics;
import io.github.ffetih.ecommerce.events.payment.AuthorizePayment;
import io.github.ffetih.ecommerce.events.payment.PaymentAuthorized;
import io.github.ffetih.ecommerce.messaging.MessagingTopics;
import io.github.ffetih.ecommerce.payment.application.PaymentService;
import io.github.ffetih.ecommerce.payment.application.port.Ledger;
import io.github.ffetih.ecommerce.payment.application.port.PaymentEvents;
import io.github.ffetih.ecommerce.payment.application.port.PaymentProvider;
import io.github.ffetih.ecommerce.payment.application.port.PaymentRepository;
import io.github.ffetih.ecommerce.payment.application.port.ProcessedEvents;
import io.github.ffetih.ecommerce.payment.application.port.Transactions;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

import java.time.Clock;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentProperties.class)
class PaymentConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    PaymentService paymentService(PaymentRepository payments, Ledger ledger, PaymentProvider provider, PaymentEvents events,
                                  ProcessedEvents processedEvents, Transactions transactions, Clock clock,
                                  PaymentProperties properties) {
        var resolver = properties.resolver();
        return new PaymentService(payments, ledger, provider, events, processedEvents, transactions, clock,
                new PaymentService.Settings(resolver.staleAfter(), resolver.batchSize()));
    }

    @Bean
    KafkaAdmin.NewTopics paymentTopics(MessagingTopics topics) {
        return topics.declare(Topics.PARTITIONS,
                List.of(PaymentAuthorized.TOPIC),
                List.of(AuthorizePayment.TOPIC));
    }
}

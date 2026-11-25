package io.github.ffetih.ecommerce.messaging;

import io.github.ffetih.ecommerce.messaging.inbox.DeadLetters;
import io.github.ffetih.ecommerce.messaging.inbox.EventReader;
import io.github.ffetih.ecommerce.messaging.inbox.Inbox;
import io.github.ffetih.ecommerce.messaging.inbox.MalformedEventException;
import io.github.ffetih.ecommerce.messaging.outbox.Outbox;
import io.github.ffetih.ecommerce.messaging.outbox.OutboxRelay;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.Clock;

/** Registers the outbox, relay, inbox and consumer error handling for any service that depends on this module. */
@AutoConfiguration
@EnableConfigurationProperties(MessagingProperties.class)
public class MessagingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Outbox outbox(DataSource dataSource, JsonMapper json, ObjectProvider<Clock> clock,
                  MessagingProperties properties, Environment environment) {
        String source = properties.source() != null
                ? properties.source()
                : environment.getProperty("spring.application.name", "unknown-service");
        return new Outbox(JdbcClient.create(dataSource), json, clock.getIfAvailable(Clock::systemUTC), source);
    }

    @Bean
    @ConditionalOnMissingBean
    OutboxRelay outboxRelay(DataSource dataSource, KafkaTemplate<String, String> kafka,
                            PlatformTransactionManager transactionManager, MessagingProperties properties) {
        return new OutboxRelay(JdbcClient.create(dataSource), kafka,
                new TransactionTemplate(transactionManager), properties.outbox());
    }

    @Bean
    @ConditionalOnMissingBean
    Inbox inbox(DataSource dataSource) {
        return new Inbox(JdbcClient.create(dataSource));
    }

    @Bean
    @ConditionalOnMissingBean
    EventReader eventReader(JsonMapper json) {
        return new EventReader(json);
    }

    /**
     * Consumer failures: retry a few times with growing pauses (a database blip, a restarting service),
     * then publish the record to {@code <topic>-dlt} ({@link DeadLetters}) so the partition moves on. Malformed messages skip
     * the retries, because reading them again gives the same result.
     */
    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafka) {
        var backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(500);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(5_000);
        var recoverer = new DeadLetterPublishingRecoverer(kafka, (record, exception) ->
                new TopicPartition(DeadLetters.topicFor(record.topic()), record.partition()));
        var handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(MalformedEventException.class);
        return handler;
    }
}

package io.github.ffetih.ecommerce.messaging;

import io.github.ffetih.ecommerce.messaging.inbox.DeadLetters;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Declares a service's topics with the platform's retention, so no topic falls back to the broker's
 * default, which the inbox retention could not be checked against.
 * <pre>{@code
 * @Bean
 * KafkaAdmin.NewTopics orderTopics(MessagingTopics topics) {
 *     return topics.declare(Topics.PARTITIONS,
 *             List.of(OrderPlaced.TOPIC),                                   // written
 *             List.of(StockReserved.TOPIC, ShipmentDispatched.TOPIC));      // read, with their dead letters
 * }
 * }</pre>
 * Every service declares what it writes and reads; creation is idempotent, so startup order does not
 * matter. With {@code spring.kafka.admin.modify-topic-configs=true}, existing topics get the
 * retention too.
 */
public final class MessagingTopics {

    private final Duration retention;
    private final Duration deadLetterRetention;

    public MessagingTopics(MessagingProperties.Topics settings) {
        this.retention = settings.retention();
        this.deadLetterRetention = settings.deadLetterRetention();
    }

    public KafkaAdmin.NewTopics declare(int partitions, Collection<String> written, Collection<String> read) {
        return new KafkaAdmin.NewTopics(topics(partitions, written, read).toArray(NewTopic[]::new));
    }

    /** The topics {@link #declare} creates: each written and read topic once, plus the dead letters of read ones. */
    public List<NewTopic> topics(int partitions, Collection<String> written, Collection<String> read) {
        Map<String, NewTopic> topics = new LinkedHashMap<>();
        written.forEach(name -> topics.putIfAbsent(name, topic(name, partitions, retention)));
        for (String name : read) {
            topics.putIfAbsent(name, topic(name, partitions, retention));
            String deadLetters = DeadLetters.topicFor(name);
            // A dead letter keeps its partition number, so its topic needs as many partitions.
            topics.putIfAbsent(deadLetters, topic(deadLetters, partitions, deadLetterRetention));
        }
        return List.copyOf(topics.values());
    }

    private static NewTopic topic(String name, int partitions, Duration retention) {
        return TopicBuilder.name(name)
                .partitions(partitions)
                .replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(retention.toMillis()))
                .build();
    }
}

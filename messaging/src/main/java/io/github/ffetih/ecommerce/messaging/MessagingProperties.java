package io.github.ffetih.ecommerce.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Settings under {@code ecommerce.messaging}.
 *
 * @param source  name written into every envelope; defaults to {@code spring.application.name}
 * @param outbox  relay settings
 * @param topics  settings of the topics a service declares
 * @param inbox   how long processed event ids are remembered
 */
@ConfigurationProperties("ecommerce.messaging")
public record MessagingProperties(String source, @DefaultValue Outbox outbox, @DefaultValue Topics topics,
                                  @DefaultValue Inbox inbox) {

    /**
     * The inbox must remember an event for as long as Kafka can deliver it again. A consumer group
     * whose offsets are lost (they expire while the service is down, or someone resets them) starts
     * again from the oldest record in the topic; an event id the inbox has forgotten would then be
     * processed a second time.
     */
    public MessagingProperties {
        if (inbox.retention().compareTo(topics.retention()) <= 0) {
            throw new IllegalArgumentException(("ecommerce.messaging.inbox.retention (%s) must be longer than "
                    + "ecommerce.messaging.topics.retention (%s): events Kafka can still redeliver would be "
                    + "processed again").formatted(inbox.retention(), topics.retention()));
        }
    }

    /**
     * @param relayEnabled    run the background relay (tests may disable it and call it directly)
     * @param pollInterval    pause between relay runs when there is nothing to publish
     * @param batchSize       maximum (topic, key) groups claimed per run
     * @param maxPerKey       maximum messages of one key published per run
     * @param sendTimeout     how long to wait for the broker to acknowledge one message
     * @param initialBackoff  delay before retrying a failed message; doubles per attempt
     * @param maxBackoff      upper bound for the retry delay
     * @param retention       how long published rows are kept for troubleshooting
     */
    public record Outbox(
            @DefaultValue("true") boolean relayEnabled,
            @DefaultValue("500ms") Duration pollInterval,
            @DefaultValue("100") int batchSize,
            @DefaultValue("50") int maxPerKey,
            @DefaultValue("5s") Duration sendTimeout,
            @DefaultValue("1s") Duration initialBackoff,
            @DefaultValue("5m") Duration maxBackoff,
            @DefaultValue("7d") Duration retention) {
    }

    /**
     * @param retention            how long Kafka keeps events ({@code retention.ms})
     * @param deadLetterRetention  how long dead letters are kept: long enough for a person to find,
     *                             fix and replay them
     */
    public record Topics(
            @DefaultValue("7d") Duration retention,
            @DefaultValue("30d") Duration deadLetterRetention) {
    }

    /**
     * @param retention  how long a processed event id is remembered; longer than the topic retention
     */
    public record Inbox(@DefaultValue("30d") Duration retention) {
    }
}

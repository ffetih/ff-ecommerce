package io.github.ffetih.ecommerce.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class MessagingTopicsTest {

    private final MessagingTopics topics =
            new MessagingTopics(new MessagingProperties.Topics(Duration.ofDays(7), Duration.ofDays(30)));

    @Test
    void declaresWrittenAndReadTopicsOnceAndADeadLetterTopicForEachReadOne() {
        Map<String, NewTopic> declared = declared(List.of("a.events", "b.events"), List.of("b.events", "c.events"));

        assertThat(declared).containsOnlyKeys("a.events", "b.events", "c.events", "b.events-dlt", "c.events-dlt");
        assertThat(declared.values()).allSatisfy(topic -> assertThat(topic.numPartitions()).isEqualTo(3));
    }

    @Test
    void eventsAndDeadLettersGetTheirOwnRetention() {
        Map<String, NewTopic> declared = declared(List.of(), List.of("c.events"));

        assertThat(declared.get("c.events").configs())
                .containsEntry(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(Duration.ofDays(7).toMillis()));
        assertThat(declared.get("c.events-dlt").configs())
                .containsEntry(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(Duration.ofDays(30).toMillis()));
    }

    private Map<String, NewTopic> declared(List<String> written, List<String> read) {
        return topics.topics(3, written, read).stream()
                .collect(Collectors.toMap(NewTopic::name, topic -> topic));
    }
}

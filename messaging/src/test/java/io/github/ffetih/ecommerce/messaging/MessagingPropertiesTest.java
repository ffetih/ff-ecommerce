package io.github.ffetih.ecommerce.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessagingPropertiesTest {

    @Test
    void theDefaultsRememberEventsLongerThanKafkaKeepsThem() {
        MessagingProperties properties = bind(Map.of());

        assertThat(properties.topics().retention()).isEqualTo(Duration.ofDays(7));
        assertThat(properties.inbox().retention()).isEqualTo(Duration.ofDays(30));
        assertThat(properties.topics().deadLetterRetention()).isEqualTo(Duration.ofDays(30));
    }

    @Test
    void anInboxThatForgetsBeforeTheTopicDoesStopsTheServiceFromStarting() {
        assertThatThrownBy(() -> bind(Map.of(
                "ecommerce.messaging.topics.retention", "14d",
                "ecommerce.messaging.inbox.retention", "14d")))
                .isInstanceOf(BindException.class)
                .rootCause()
                .hasMessageContaining("must be longer than ecommerce.messaging.topics.retention");
    }

    private static MessagingProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("ecommerce.messaging", MessagingProperties.class);
    }
}

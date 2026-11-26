package io.github.ffetih.ecommerce.messaging.outbox;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;

import java.time.Duration;
import java.util.Map;

/**
 * Caps how long a Kafka send may block before it even returns its future.
 *
 * <p>{@code KafkaProducer.send} waits for topic metadata first, up to {@code max.block.ms}: 60 seconds by
 * default. Only then does the relay's own timeout ({@code future.get(sendTimeout)}) start. With the broker
 * down, or one topic missing, every attempt therefore held the relay thread for a minute, and every other
 * key waited behind it. The Kafka-down drill showed it in Jaeger: each failed "outbox relay" span lasted
 * 60 s although the send timeout was 5 s.
 *
 * <p>This limits {@code max.block.ms} to the relay's send timeout, so one attempt costs at most that long.
 * A service that sets {@code max.block.ms} itself keeps its own value.
 */
public class ProducerBlockLimit implements BeanPostProcessor {

    private final Duration limit;

    public ProducerBlockLimit(Duration limit) {
        this.limit = limit;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof DefaultKafkaProducerFactory<?, ?> factory
                && !factory.getConfigurationProperties().containsKey(ProducerConfig.MAX_BLOCK_MS_CONFIG)) {
            factory.updateConfigs(Map.of(ProducerConfig.MAX_BLOCK_MS_CONFIG, limit.toMillis()));
        }
        return bean;
    }
}

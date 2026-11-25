package io.github.ffetih.ecommerce.messaging.inbox;

/**
 * Naming of dead-letter topics, set explicitly instead of relying on the framework default
 * (Spring Kafka changed its default suffix from {@code .DLT} to {@code -dlt} between major versions).
 * A dead-letter topic must exist with at least as many partitions as its source topic, because a
 * failed record keeps its partition number.
 */
public final class DeadLetters {

    public static final String SUFFIX = "-dlt";

    private DeadLetters() {
    }

    public static String topicFor(String topic) {
        return topic + SUFFIX;
    }
}

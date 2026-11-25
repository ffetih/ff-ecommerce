package io.github.ffetih.ecommerce.messaging.inbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ListenerExecutionFailedException;

/**
 * Counts records moved to a dead-letter topic. A dead-lettered record is a saga step that did not
 * happen; nothing retries it, so someone has to look. Any increase is worth an alert.
 *
 * <p>The count goes up only after the dead-letter topic has taken the record. If publishing there
 * fails, the delegate throws, nothing is counted, and the error handler tries the record again.
 * The counter appears with its first dead letter, tagged with the topic the record came from and
 * the exception that stopped it.
 */
public final class DeadLetterCounter implements ConsumerRecordRecoverer {

    public static final String METRIC = "messaging.dead.letters";

    private final ConsumerRecordRecoverer deadLetterPublisher;
    private final MeterRegistry registry;

    public DeadLetterCounter(ConsumerRecordRecoverer deadLetterPublisher, MeterRegistry registry) {
        this.deadLetterPublisher = deadLetterPublisher;
        this.registry = registry;
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception failure) {
        deadLetterPublisher.accept(record, failure);
        Counter.builder(METRIC)
                .description("Records moved to a dead-letter topic after their processing failed")
                .tag("topic", record.topic())
                .tag("exception", reason(failure).getClass().getSimpleName())
                .register(registry)
                .increment();
    }

    /** The listener's own exception, not the container's wrapper around it. */
    private static Throwable reason(Exception failure) {
        return failure instanceof ListenerExecutionFailedException && failure.getCause() != null
                ? failure.getCause()
                : failure;
    }
}

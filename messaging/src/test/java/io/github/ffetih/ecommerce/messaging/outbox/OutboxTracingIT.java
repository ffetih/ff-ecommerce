package io.github.ffetih.ecommerce.messaging.outbox;

import io.github.ffetih.ecommerce.messaging.MessagingTestcontainers;
import io.github.ffetih.ecommerce.messaging.TestTracer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static io.github.ffetih.ecommerce.messaging.MessagingTestcontainers.consume;
import static io.github.ffetih.ecommerce.messaging.MessagingTestcontainers.createTopic;
import static io.github.ffetih.ecommerce.messaging.MessagingTestcontainers.uniqueTopic;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The relay sends from its own thread, long after the request that wrote the event has finished. The trace
 * must survive that gap: the Kafka record carries the trace id of the work that appended the event.
 */
@SpringBootTest
@Import(MessagingTestcontainers.class)
class OutboxTracingIT {

    @Autowired
    Outbox outbox;

    @Autowired
    OutboxRelay relay;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    ObservationRegistry observations;

    @Autowired
    JdbcClient jdbc;

    record Placed(String orderId) {
    }

    @Test
    void theKafkaRecordContinuesTheTraceOfTheRequestThatWroteTheEvent() {
        String topic = uniqueTopic("traced");
        createTopic(topic, 1);

        // Stands in for the HTTP server observation of "POST /orders".
        Observation request = Observation.start("http.server.requests", observations);
        UUID eventId;
        try (Observation.Scope ignored = request.openScope()) {
            eventId = transactions.execute(status -> outbox.append(topic, "order-1", "test.placed", 1, new Placed("order-1")));
        } finally {
            request.stop();
        }
        String requestTrace = TestTracer.traceIdOf(request);

        // The row remembers the request's trace...
        assertThat(TestTracer.traceIdOf(storedTraceparent(eventId))).isEqualTo(requestTrace);

        // ...and the relay, running later with no request around, sends the record in that same trace.
        relay.relayOnce();
        ConsumerRecord<String, String> record = single(consume(topic, 1, Duration.ofSeconds(10)));
        assertThat(TestTracer.traceIdOf(traceparentHeader(record))).isEqualTo(requestTrace);
    }

    @Test
    void anEventWrittenOutsideAnyTraceStartsItsOwnTrace() {
        String topic = uniqueTopic("untraced");
        createTopic(topic, 1);

        UUID first = transactions.execute(status -> outbox.append(topic, "a", "test.placed", 1, new Placed("a")));
        UUID second = transactions.execute(status -> outbox.append(topic, "b", "test.placed", 1, new Placed("b")));

        // A scheduled job, for example: each append begins a trace of its own, so two events never share one.
        String firstTrace = TestTracer.traceIdOf(storedTraceparent(first));
        String secondTrace = TestTracer.traceIdOf(storedTraceparent(second));
        assertThat(firstTrace).isNotEqualTo(secondTrace);

        relay.relayOnce();
        List<ConsumerRecord<String, String>> records = consume(topic, 2, Duration.ofSeconds(10));
        assertThat(records).extracting(r -> TestTracer.traceIdOf(traceparentHeader(r)))
                .containsExactlyInAnyOrder(firstTrace, secondTrace);
    }

    private String storedTraceparent(UUID eventId) {
        return jdbc.sql("SELECT traceparent FROM outbox_messages WHERE event_id = :eventId")
                .param("eventId", eventId)
                .query(String.class)
                .single();
    }

    private static String traceparentHeader(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader("traceparent");
        assertThat(header).as("traceparent header on the record").isNotNull();
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    private static ConsumerRecord<String, String> single(List<ConsumerRecord<String, String>> records) {
        assertThat(records).hasSize(1);
        return records.getFirst();
    }
}

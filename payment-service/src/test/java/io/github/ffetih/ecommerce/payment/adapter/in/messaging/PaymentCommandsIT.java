package io.github.ffetih.ecommerce.payment.adapter.in.messaging;

import io.github.ffetih.ecommerce.events.payment.AuthorizePayment;
import io.github.ffetih.ecommerce.events.payment.CapturePayment;
import io.github.ffetih.ecommerce.events.payment.PaymentAuthorized;
import io.github.ffetih.ecommerce.events.payment.PaymentCaptured;
import io.github.ffetih.ecommerce.events.payment.PaymentDeclined;
import io.github.ffetih.ecommerce.events.payment.PaymentRefundFailed;
import io.github.ffetih.ecommerce.events.payment.PaymentRefunded;
import io.github.ffetih.ecommerce.events.payment.RefundPayment;
import io.github.ffetih.ecommerce.events.payment.PaymentVoided;
import io.github.ffetih.ecommerce.events.payment.VoidPayment;
import io.github.ffetih.ecommerce.messaging.EventEnvelope;
import io.github.ffetih.ecommerce.payment.adapter.out.provider.SimulatedPaymentProvider;
import io.github.ffetih.ecommerce.payment.application.PaymentService;
import io.github.ffetih.ecommerce.payment.application.port.Ledger;
import io.github.ffetih.ecommerce.payment.domain.LedgerTransaction;
import io.github.ffetih.ecommerce.payment.domain.Payment;
import io.github.ffetih.ecommerce.payment.support.TestcontainersConfiguration;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** payment-service on Kafka: the test plays order-service, sending commands and reading the answers. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PaymentCommandsIT {

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JsonMapper json;

    @Autowired
    PaymentService payments;

    @Autowired
    Ledger ledger;

    @Test
    void authorizesCapturesAndBooksTheLedger() throws Exception {
        UUID orderId = UUID.randomUUID();

        send(orderId, AuthorizePayment.TYPE, authorize(orderId, SimulatedPaymentProvider.OK));
        assertThat(paymentEventTypes(orderId, 1)).containsExactly(PaymentAuthorized.TYPE);

        send(orderId, CapturePayment.TYPE, new CapturePayment(orderId));
        assertThat(paymentEventTypes(orderId, 2)).containsExactly(PaymentAuthorized.TYPE, PaymentCaptured.TYPE);

        Payment captured = payments.forOrder(orderId).orElseThrow();
        assertThat(captured.status()).isEqualTo(Payment.Status.CAPTURED);
        assertThat(ledger.transactionsOf(captured.id())).extracting(LedgerTransaction::kind)
                .containsExactly(LedgerTransaction.Kind.CAPTURE);
    }

    @Test
    void aDeclinedCardIsAnswered() throws Exception {
        UUID orderId = UUID.randomUUID();

        send(orderId, AuthorizePayment.TYPE, authorize(orderId, SimulatedPaymentProvider.DECLINE));

        assertThat(paymentEventTypes(orderId, 1)).containsExactly(PaymentDeclined.TYPE);
    }

    @Test
    void anUnansweredAuthorizationIsResolvedNotDeclined() throws Exception {
        UUID orderId = UUID.randomUUID();

        send(orderId, AuthorizePayment.TYPE, authorize(orderId, SimulatedPaymentProvider.SLOW));
        awaitUntil(() -> payments.forOrder(orderId).isPresent());

        // The command handler got no answer; the resolver asks again with the same key and gets the approval.
        assertThat(paymentEventTypes(orderId, 1)).containsExactly(PaymentAuthorized.TYPE);
        assertThat(payments.forOrder(orderId).orElseThrow().status()).isEqualTo(Payment.Status.AUTHORIZED);
    }

    @Test
    void aCancelledOrdersHoldIsReleased() throws Exception {
        UUID orderId = UUID.randomUUID();
        send(orderId, AuthorizePayment.TYPE, authorize(orderId, SimulatedPaymentProvider.OK));

        send(orderId, VoidPayment.TYPE, new VoidPayment(orderId));

        assertThat(paymentEventTypes(orderId, 2)).containsExactly(PaymentAuthorized.TYPE, PaymentVoided.TYPE);
        assertThat(payments.forOrder(orderId).orElseThrow().status()).isEqualTo(Payment.Status.VOIDED);
    }

    @Test
    void refundsAReturnOnceAndRefusesWhatWouldExceedTheCapture() throws Exception {
        UUID orderId = UUID.randomUUID();
        send(orderId, AuthorizePayment.TYPE, authorize(orderId, SimulatedPaymentProvider.OK));
        send(orderId, CapturePayment.TYPE, new CapturePayment(orderId));
        UUID returnId = UUID.randomUUID();

        send(orderId, RefundPayment.TYPE, new RefundPayment(orderId, returnId, new BigDecimal("300.00"), "TRY"));
        send(orderId, RefundPayment.TYPE, new RefundPayment(orderId, returnId, new BigDecimal("300.00"), "TRY"));
        send(orderId, RefundPayment.TYPE, new RefundPayment(orderId, UUID.randomUUID(), new BigDecimal("100.00"), "TRY"));

        assertThat(paymentEventTypes(orderId, 4)).containsExactly(PaymentAuthorized.TYPE, PaymentCaptured.TYPE,
                PaymentRefunded.TYPE, PaymentRefundFailed.TYPE);
        Payment payment = payments.forOrder(orderId).orElseThrow();
        assertThat(ledger.transactionsOf(payment.id())).extracting(LedgerTransaction::kind)
                .containsExactly(LedgerTransaction.Kind.CAPTURE, LedgerTransaction.Kind.REFUND);
    }

    // --- helpers -----------------------------------------------------------------------------

    private static AuthorizePayment authorize(UUID orderId, String cardToken) {
        return new AuthorizePayment(orderId, UUID.randomUUID(), new BigDecimal("349.90"), "TRY", cardToken);
    }

    private void send(UUID orderId, String type, Object data) throws Exception {
        var envelope = new EventEnvelope(UUID.randomUUID(), type, 1, Instant.now(), "payment-it", json.valueToTree(data));
        kafka.send(AuthorizePayment.TOPIC, orderId.toString(), json.writeValueAsString(envelope)).get();
    }

    private List<String> paymentEventTypes(UUID orderId, int expected) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, TestcontainersConfiguration.kafkaBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "payment-it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<String> types = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(PaymentAuthorized.TOPIC));
            Instant deadline = Instant.now().plusSeconds(20);
            Instant settleUntil = null;
            while (Instant.now().isBefore(deadline) && (settleUntil == null || Instant.now().isBefore(settleUntil))) {
                for (var record : consumer.poll(Duration.ofMillis(250))) {
                    if (orderId.toString().equals(record.key())) {
                        types.add(json.readValue(record.value(), EventEnvelope.class).eventType());
                    }
                }
                if (types.size() >= expected && settleUntil == null) {
                    settleUntil = Instant.now().plusSeconds(2); // give an unexpected extra event the chance to show up
                }
            }
        }
        return types;
    }

    private static void awaitUntil(BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(20);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Condition not met within 20s");
            }
            Thread.sleep(100);
        }
    }
}

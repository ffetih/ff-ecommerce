package io.github.ffetih.ecommerce.payment.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    private static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");

    private static Payment started() {
        return Payment.start(UUID.randomUUID(), UUID.randomUUID(), Money.of(new BigDecimal("1299.90"), "TRY"),
                "tok_ok", NOW);
    }

    private static Payment authorized() {
        return started().authorized("AUTH-1", NOW);
    }

    @Test
    void startsWaitingForTheAuthorization() {
        Payment payment = started();

        assertThat(payment.status()).isEqualTo(Payment.Status.AUTHORIZING);
        assertThat(payment.operationInFlight()).isEqualTo(Payment.Operation.AUTHORIZE);
        assertThat(payment.inFlight()).isTrue();
    }

    @Test
    void approvalKeepsTheProviderReferenceAndIsIdempotent() {
        Payment payment = authorized();

        assertThat(payment.status()).isEqualTo(Payment.Status.AUTHORIZED);
        assertThat(payment.providerReference()).isEqualTo("AUTH-1");
        assertThat(payment.inFlight()).isFalse();
        assertThat(payment.authorized("AUTH-1", NOW)).isSameAs(payment);
    }

    @Test
    void aDeclineIsFinal() {
        Payment declined = started().declined("Card declined", NOW);

        assertThat(declined.status()).isEqualTo(Payment.Status.DECLINED);
        assertThat(declined.failureReason()).isEqualTo("Card declined");
        assertThat(declined.declined("Card declined", NOW)).isSameAs(declined);
        assertThatThrownBy(() -> declined.authorized("AUTH-1", NOW))
                .isInstanceOf(IllegalPaymentTransitionException.class);
        assertThatThrownBy(() -> authorized().declined("late", NOW))
                .isInstanceOf(IllegalPaymentTransitionException.class);
    }

    @Test
    void aVoidDuringTheAuthorizationIsRememberedAndAppliedOnApproval() {
        Payment remembered = started().requestVoid(NOW);

        assertThat(remembered.status()).isEqualTo(Payment.Status.AUTHORIZING);
        assertThat(remembered.voidRequested()).isTrue();
        assertThat(remembered.requestVoid(NOW)).isSameAs(remembered);

        Payment approved = remembered.authorized("AUTH-1", NOW);
        assertThat(approved.status()).isEqualTo(Payment.Status.VOIDING);
        assertThat(approved.operationInFlight()).isEqualTo(Payment.Operation.VOID);
        assertThat(approved.voided(NOW).status()).isEqualTo(Payment.Status.VOIDED);
    }

    @Test
    void aVoidRememberedBeforeADeclineHasNothingToRelease() {
        Payment declined = started().requestVoid(NOW).declined("Card declined", NOW);

        assertThat(declined.status()).isEqualTo(Payment.Status.DECLINED);
        assertThat(declined.requestVoid(NOW)).isSameAs(declined);
    }

    @Test
    void anAuthorizedHoldCanBeReleased() {
        Payment voiding = authorized().requestVoid(NOW);

        assertThat(voiding.status()).isEqualTo(Payment.Status.VOIDING);
        assertThat(voiding.requestVoid(NOW)).isSameAs(voiding);
        Payment voided = voiding.voided(NOW);
        assertThat(voided.voided(NOW)).isSameAs(voided);
        assertThatThrownBy(() -> voided.requestCapture(NOW)).isInstanceOf(IllegalPaymentTransitionException.class);
    }

    @Test
    void captureNeedsAnAuthorizationAndIsIdempotent() {
        assertThatThrownBy(() -> started().requestCapture(NOW)).isInstanceOf(IllegalPaymentTransitionException.class);

        Payment capturing = authorized().requestCapture(NOW);
        assertThat(capturing.status()).isEqualTo(Payment.Status.CAPTURING);
        assertThat(capturing.operationInFlight()).isEqualTo(Payment.Operation.CAPTURE);
        assertThat(capturing.requestCapture(NOW)).isSameAs(capturing);

        Payment captured = capturing.captured(NOW);
        assertThat(captured.status()).isEqualTo(Payment.Status.CAPTURED);
        assertThat(captured.requestCapture(NOW)).isSameAs(captured);
        assertThat(captured.captured(NOW)).isSameAs(captured);
    }

    @Test
    void capturedMoneyCannotBeVoided() {
        Payment captured = authorized().requestCapture(NOW).captured(NOW);

        assertThatThrownBy(() -> captured.requestVoid(NOW))
                .isInstanceOf(IllegalPaymentTransitionException.class)
                .hasMessageContaining("Cannot void payment").hasMessageContaining("CAPTURED");
    }

    @Test
    void aFailedCaptureKeepsItsReason() {
        Payment failed = authorized().requestCapture(NOW).captureFailed("Authorization expired", NOW);

        assertThat(failed.status()).isEqualTo(Payment.Status.CAPTURE_FAILED);
        assertThat(failed.failureReason()).isEqualTo("Authorization expired");
        assertThat(failed.requestCapture(NOW)).isSameAs(failed);
        assertThatThrownBy(() -> failed.requestVoid(NOW)).isInstanceOf(IllegalPaymentTransitionException.class);
    }

    @Test
    void eachOperationHasItsOwnStableIdempotencyKey() {
        Payment payment = started();

        assertThat(payment.idempotencyKey(Payment.Operation.AUTHORIZE)).isEqualTo(payment.id() + ":authorize");
        assertThat(payment.authorized("AUTH-1", NOW).idempotencyKey(Payment.Operation.AUTHORIZE))
                .isEqualTo(payment.idempotencyKey(Payment.Operation.AUTHORIZE));
        assertThat(payment.idempotencyKey(Payment.Operation.CAPTURE))
                .isNotEqualTo(payment.idempotencyKey(Payment.Operation.AUTHORIZE));
    }

    @Test
    void moneyMustBePositiveAndExact() {
        assertThat(Money.of(new BigDecimal("10"), "TRY").amount()).isEqualByComparingTo("10.00");
        assertThat(Money.of(new BigDecimal("10"), "TRY").amount().scale()).isEqualTo(2);
        assertThatThrownBy(() -> Money.of(BigDecimal.ZERO, "TRY")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of(new BigDecimal("1.005"), "TRY"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

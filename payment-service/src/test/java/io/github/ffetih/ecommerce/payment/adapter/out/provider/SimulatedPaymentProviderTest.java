package io.github.ffetih.ecommerce.payment.adapter.out.provider;

import io.github.ffetih.ecommerce.payment.application.port.ProviderResult;
import io.github.ffetih.ecommerce.payment.application.port.ProviderResult.Approved;
import io.github.ffetih.ecommerce.payment.application.port.ProviderResult.Declined;
import io.github.ffetih.ecommerce.payment.application.port.ProviderResult.Unknown;
import io.github.ffetih.ecommerce.payment.domain.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class SimulatedPaymentProviderTest {

    private static final Money AMOUNT = Money.of(new BigDecimal("100.00"), "TRY");

    private final SimulatedPaymentProvider provider = new SimulatedPaymentProvider();

    private String authorize(String key, String token) {
        return ((Approved) provider.authorize(key, AMOUNT, token)).reference();
    }

    @Test
    void repeatingACallWithTheSameKeyRepeatsTheFirstAnswer() {
        ProviderResult first = provider.authorize("p1:authorize", AMOUNT, SimulatedPaymentProvider.OK);

        assertThat(first).isInstanceOf(Approved.class);
        assertThat(provider.authorize("p1:authorize", AMOUNT, SimulatedPaymentProvider.OK)).isEqualTo(first);
        assertThat(provider.holdsPlaced()).isEqualTo(1);
    }

    @Test
    void declinesTheDeclineTokenAndUnknownCards() {
        assertThat(provider.authorize("p1:authorize", AMOUNT, SimulatedPaymentProvider.DECLINE))
                .isEqualTo(new Declined("Card declined"));
        assertThat(provider.authorize("p2:authorize", AMOUNT, "tok_nonsense"))
                .isEqualTo(new Declined("Unknown card token"));
        assertThat(provider.holdsPlaced()).isZero();
    }

    @Test
    void aSlowAuthorizationIsPlacedButItsFirstAnswerIsLost() {
        assertThat(provider.authorize("p1:authorize", AMOUNT, SimulatedPaymentProvider.SLOW)).isInstanceOf(Unknown.class);
        assertThat(provider.holdsPlaced()).isEqualTo(1);

        assertThat(provider.authorize("p1:authorize", AMOUNT, SimulatedPaymentProvider.SLOW)).isInstanceOf(Approved.class);
        assertThat(provider.holdsPlaced()).as("asking again did not place a second hold").isEqualTo(1);
    }

    @Test
    void capturesAnAuthorizationOnce() {
        String hold = authorize("p1:authorize", SimulatedPaymentProvider.OK);

        ProviderResult captured = provider.capture("p1:capture", hold, AMOUNT);

        assertThat(captured).isInstanceOf(Approved.class);
        assertThat(provider.capture("p1:capture", hold, AMOUNT)).isEqualTo(captured);
        assertThat(provider.capture("p1:capture-again", hold, AMOUNT)).isEqualTo(new Declined("Authorization is captured"));
        assertThat(provider.voidAuthorization("p1:void", hold)).isEqualTo(new Declined("Authorization is captured"));
    }

    @Test
    void theCaptureFailsTokenAuthorizesButCannotBeCaptured() {
        String hold = authorize("p1:authorize", SimulatedPaymentProvider.CAPTURE_FAILS);

        assertThat(provider.capture("p1:capture", hold, AMOUNT)).isEqualTo(new Declined("Authorization expired"));
    }

    @Test
    void aVoidedHoldCannotBeCaptured() {
        String hold = authorize("p1:authorize", SimulatedPaymentProvider.OK);

        assertThat(provider.voidAuthorization("p1:void", hold)).isInstanceOf(Approved.class);
        assertThat(provider.capture("p1:capture", hold, AMOUNT)).isEqualTo(new Declined("Authorization is voided"));
    }

    @Test
    void neverCapturesMoreThanWasHeld() {
        String hold = authorize("p1:authorize", SimulatedPaymentProvider.OK);

        assertThat(provider.capture("p1:capture", hold, Money.of(new BigDecimal("100.01"), "TRY")))
                .isEqualTo(new Declined("Amount exceeds the authorization"));
        assertThat(provider.capture("p2:capture", "AUTH-unknown", AMOUNT)).isEqualTo(new Declined("Unknown authorization"));
    }

    @Test
    void refundsCapturedMoneyOncePerKeyAndNeverMoreThanWasCaptured() {
        String hold = authorize("p1:authorize", SimulatedPaymentProvider.OK);
        assertThat(provider.refund("p1:refund:r0", hold, Money.of(new BigDecimal("10.00"), "TRY")))
                .as("nothing captured yet").isEqualTo(new Declined("Nothing captured to refund"));
        provider.capture("p1:capture", hold, AMOUNT);

        ProviderResult first = provider.refund("p1:refund:r1", hold, Money.of(new BigDecimal("60.00"), "TRY"));
        assertThat(first).isInstanceOf(Approved.class);
        assertThat(provider.refund("p1:refund:r1", hold, Money.of(new BigDecimal("60.00"), "TRY"))).isEqualTo(first);
        assertThat(provider.refunded(hold)).as("the retry gave nothing more back").isEqualByComparingTo("60.00");
        assertThat(provider.refund("p1:refund:r2", hold, Money.of(new BigDecimal("50.00"), "TRY")))
                .isEqualTo(new Declined("Refunds exceed the captured amount"));
    }

    @Test
    void theRefundFailsTokenCannotBeRefunded() {
        String hold = authorize("p1:authorize", SimulatedPaymentProvider.REFUND_FAILS);
        provider.capture("p1:capture", hold, AMOUNT);

        assertThat(provider.refund("p1:refund:r1", hold, AMOUNT)).isEqualTo(new Declined("Card account closed"));
    }
}

package io.github.ffetih.ecommerce.payment.adapter.out.provider;

import io.github.ffetih.ecommerce.payment.application.port.PaymentProvider;
import io.github.ffetih.ecommerce.payment.application.port.ProviderResult;
import io.github.ffetih.ecommerce.payment.domain.Money;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A card provider inside the service, for local runs and tests. The card token picks the behaviour:
 * <ul>
 *   <li>{@code tok_ok}: approved.</li>
 *   <li>{@code tok_decline}: declined.</li>
 *   <li>{@code tok_slow}: the first authorize call is approved but its answer is lost (Unknown);
 *       asking again with the same key returns the approval.</li>
 *   <li>{@code tok_capture_fails}: approved, but the capture is declined later.</li>
 *   <li>anything else: declined as an unknown card.</li>
 * </ul>
 * Like a real provider it acts at most once per idempotency key and repeats the first answer. Unlike
 * one, it keeps its state in memory and forgets it on restart.
 */
@Component
public class SimulatedPaymentProvider implements PaymentProvider {

    public static final String OK = "tok_ok";
    public static final String DECLINE = "tok_decline";
    public static final String SLOW = "tok_slow";
    public static final String CAPTURE_FAILS = "tok_capture_fails";

    private enum State { AUTHORIZED, CAPTURED, VOIDED }

    private record Authorization(String cardToken, Money amount, State state) {

        Authorization moveTo(State next) {
            return new Authorization(cardToken, amount, next);
        }
    }

    /** First answer per idempotency key. */
    private final Map<String, ProviderResult> answers = new ConcurrentHashMap<>();
    /** Holds on cards, by authorization reference. */
    private final Map<String, Authorization> authorizations = new ConcurrentHashMap<>();

    @Override
    public ProviderResult authorize(String idempotencyKey, Money amount, String cardToken) {
        AtomicBoolean firstCall = new AtomicBoolean();
        ProviderResult answer = answers.computeIfAbsent(idempotencyKey, key -> {
            firstCall.set(true);
            return switch (cardToken) {
                case OK, SLOW, CAPTURE_FAILS -> {
                    String reference = "AUTH-" + UUID.randomUUID();
                    authorizations.put(reference, new Authorization(cardToken, amount, State.AUTHORIZED));
                    yield new ProviderResult.Approved(reference);
                }
                case DECLINE -> new ProviderResult.Declined("Card declined");
                default -> new ProviderResult.Declined("Unknown card token");
            };
        });
        if (firstCall.get() && SLOW.equals(cardToken)) {
            return new ProviderResult.Unknown("Timed out waiting for the provider");
        }
        return answer;
    }

    @Override
    public ProviderResult capture(String idempotencyKey, String authorization, Money amount) {
        return answers.computeIfAbsent(idempotencyKey, key -> {
            Authorization hold = authorizations.get(authorization);
            if (hold == null) {
                return new ProviderResult.Declined("Unknown authorization");
            }
            if (hold.state() != State.AUTHORIZED) {
                return new ProviderResult.Declined("Authorization is " + hold.state().name().toLowerCase(Locale.ROOT));
            }
            if (CAPTURE_FAILS.equals(hold.cardToken())) {
                return new ProviderResult.Declined("Authorization expired");
            }
            if (amount.amount().compareTo(hold.amount().amount()) > 0 || !amount.currency().equals(hold.amount().currency())) {
                return new ProviderResult.Declined("Amount exceeds the authorization");
            }
            authorizations.put(authorization, hold.moveTo(State.CAPTURED));
            return new ProviderResult.Approved("CAP-" + UUID.randomUUID());
        });
    }

    @Override
    public ProviderResult voidAuthorization(String idempotencyKey, String authorization) {
        return answers.computeIfAbsent(idempotencyKey, key -> {
            Authorization hold = authorizations.get(authorization);
            if (hold == null) {
                return new ProviderResult.Declined("Unknown authorization");
            }
            if (hold.state() == State.CAPTURED) {
                return new ProviderResult.Declined("Authorization is captured");
            }
            authorizations.put(authorization, hold.moveTo(State.VOIDED));
            return new ProviderResult.Approved("VOID-" + UUID.randomUUID());
        });
    }

    /** How many holds were really placed on cards: a retried call must not add one. */
    int holdsPlaced() {
        return authorizations.size();
    }
}

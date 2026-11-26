package io.github.ffetih.ecommerce.payment.application.port;

/** The provider's answer to one call. Three outcomes, not two: no answer is not a "no" (ADR-0009). */
public sealed interface ProviderResult {

    /** The provider did it; {@code reference} is its id for the authorization, capture or void. */
    record Approved(String reference) implements ProviderResult {
    }

    /** The provider refused, for good: a declined card, an expired authorization. */
    record Declined(String reason) implements ProviderResult {
    }

    /**
     * No answer: a timeout or a lost connection. The provider may or may not have acted, so the only
     * safe next step is to ask again with the same idempotency key.
     */
    record Unknown(String cause) implements ProviderResult {
    }
}

package io.github.ffetih.ecommerce.payment.application.port;

import io.github.ffetih.ecommerce.payment.domain.Money;

/**
 * The card payment provider. Every call carries an idempotency key: the provider acts at most once per
 * key and answers a repeated call with the first outcome, which makes retrying after a timeout safe.
 * Callers never hold a database transaction open across a call.
 */
public interface PaymentProvider {

    /** Holds the amount on the card. */
    ProviderResult authorize(String idempotencyKey, Money amount, String cardToken);

    /** Takes the held money. */
    ProviderResult capture(String idempotencyKey, String authorization, Money amount);

    /** Releases the hold without taking any money. */
    ProviderResult voidAuthorization(String idempotencyKey, String authorization);

    /** Gives part or all of the captured money back. */
    ProviderResult refund(String idempotencyKey, String authorization, Money amount);
}

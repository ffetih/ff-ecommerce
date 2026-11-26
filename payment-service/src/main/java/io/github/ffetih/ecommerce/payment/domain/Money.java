package io.github.ffetih.ecommerce.payment.domain;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * Payment context's own copy of this value type (bounded contexts share no domain classes). A positive
 * amount in one currency, stored with the currency's number of decimals; more decimals are rejected,
 * never rounded: a payment is for exactly the amount the order asked for.
 */
public record Money(BigDecimal amount, Currency currency) {

    public Money {
        Objects.requireNonNull(amount, "amount is required");
        Objects.requireNonNull(currency, "currency is required");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("A payment amount must be positive: " + amount);
        }
        int digits = currency.getDefaultFractionDigits();
        if (amount.stripTrailingZeros().scale() > digits) {
            throw new IllegalArgumentException(
                    "Amount " + amount + " has more than " + digits + " decimals for " + currency);
        }
        amount = amount.setScale(digits);
    }

    public static Money of(BigDecimal amount, String currencyCode) {
        return new Money(amount, Currency.getInstance(currencyCode));
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency.getCurrencyCode();
    }
}

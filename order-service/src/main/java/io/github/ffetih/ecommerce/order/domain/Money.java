package io.github.ffetih.ecommerce.order.domain;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * Order context's own copy of this value type: bounded contexts do not share domain classes, so the
 * catalog can change its model without breaking orders. Only the JSON on the wire is shared.
 * <p>
 * Non-negative amount in one currency, always stored with the currency's number of decimals.
 * Amounts with more decimals are rejected instead of being rounded silently.
 */
public record Money(BigDecimal amount, Currency currency) {

    public Money {
        Objects.requireNonNull(amount, "amount is required");
        Objects.requireNonNull(currency, "currency is required");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("Amount cannot be negative: " + amount);
        }
        int digits = currency.getDefaultFractionDigits();
        if (amount.stripTrailingZeros().scale() > digits) {
            throw new IllegalArgumentException(
                    "Amount " + amount + " has more than " + digits + " decimals for " + currency);
        }
        amount = amount.setScale(digits);
    }

    public static Money of(String amount, String currencyCode) {
        return new Money(new BigDecimal(amount), Currency.getInstance(currencyCode));
    }

    public Money times(int quantity) {
        if (quantity < 0) {
            throw new IllegalArgumentException("Quantity cannot be negative: " + quantity);
        }
        return new Money(amount.multiply(BigDecimal.valueOf(quantity)), currency);
    }

    public Money plus(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Cannot add " + other.currency + " to " + currency);
        }
        return new Money(amount.add(other.amount), currency);
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency.getCurrencyCode();
    }
}

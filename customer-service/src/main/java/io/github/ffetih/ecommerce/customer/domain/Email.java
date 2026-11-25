package io.github.ffetih.ecommerce.customer.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An email address, normalized for comparison: trimmed and lower-cased with {@link Locale#ROOT}.
 * With a Turkish default locale {@code "ILKER".toLowerCase()} gives {@code "ılker"} (dotless ı),
 * which would let the same person register twice.
 */
public record Email(String value) {

    // Deliberately simple: real validation happens when we send a verification mail.
    private static final Pattern FORMAT = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    public Email {
        Objects.requireNonNull(value, "email is required");
        value = value.trim().toLowerCase(Locale.ROOT);
        if (value.length() > 320 || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid email address: " + value);
        }
    }

    public static Email of(String value) {
        return new Email(value);
    }

    @Override
    public String toString() {
        return value;
    }
}

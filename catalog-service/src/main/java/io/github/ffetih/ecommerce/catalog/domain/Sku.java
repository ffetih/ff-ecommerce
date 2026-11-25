package io.github.ffetih.ecommerce.catalog.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Stock keeping unit: the product identifier shared by every service.
 * Normalized to upper case with {@link Locale#ROOT}, so a Turkish default locale cannot turn
 * {@code "i"} into {@code "İ"} and make the same SKU look like two different products.
 */
public record Sku(String value) implements Comparable<Sku> {

    private static final Pattern FORMAT = Pattern.compile("[A-Z0-9][A-Z0-9-]{1,63}");

    public Sku {
        Objects.requireNonNull(value, "SKU is required");
        value = value.trim().toUpperCase(Locale.ROOT);
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid SKU '" + value + "': use 2-64 letters, digits or '-'");
        }
    }

    public static Sku of(String value) {
        return new Sku(value);
    }

    @Override
    public int compareTo(Sku other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}

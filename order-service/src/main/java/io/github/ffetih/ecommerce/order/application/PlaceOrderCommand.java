package io.github.ffetih.ecommerce.order.application;

import io.github.ffetih.ecommerce.order.domain.Sku;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What the client asked for. Prices are deliberately absent: they come from the catalog.
 *
 * @param shippingAddress optional; the customer's stored address is used when absent
 * @param cardToken       the payment method, as the payment provider knows it; never a card number
 */
public record PlaceOrderCommand(UUID customerId, List<Line> lines, String shippingAddress, String cardToken) {

    public record Line(Sku sku, int quantity) {
    }

    public PlaceOrderCommand {
        Objects.requireNonNull(customerId, "customerId is required");
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("An order needs at least one line");
        }
        lines = List.copyOf(lines);
        shippingAddress = shippingAddress == null || shippingAddress.isBlank() ? null : shippingAddress.trim();
        if (cardToken == null || cardToken.isBlank()) {
            throw new IllegalArgumentException("A payment method (card token) is required");
        }
        cardToken = cardToken.trim();
    }

    /**
     * SHA-256 of the request's meaning. Two requests with the same key must also have the same
     * fingerprint; otherwise the client reused a key for a different order, which is a client bug.
     * Line order does not matter.
     */
    public String fingerprint() {
        StringBuilder canonical = new StringBuilder()
                .append(customerId).append('|')
                .append(shippingAddress == null ? "" : shippingAddress).append('|')
                .append(cardToken).append('|');
        lines.stream()
                .sorted(Comparator.comparing(Line::sku))
                .forEach(line -> canonical.append(line.sku()).append(':').append(line.quantity()).append(';'));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}

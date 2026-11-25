package io.github.ffetih.ecommerce.order.application;

import java.util.Objects;
import java.util.UUID;

/**
 * Who is asking, in the terms this service cares about: a customer acting for themselves, or the
 * back office acting for anyone. Adapters build it from the access token; the application decides
 * with it, without knowing about tokens or Spring Security.
 *
 * @param customerId  the customer's id; {@code null} for the back office
 * @param anyCustomer true for the back office, which may act for every customer
 */
public record Requester(UUID customerId, boolean anyCustomer) {

    /** Exactly one of the two: a customer has an id, the back office has none. */
    public Requester {
        if (anyCustomer == (customerId != null)) {
            throw new IllegalArgumentException("A requester is either a customer with an id or the back office");
        }
    }

    public static Requester customer(UUID customerId) {
        return new Requester(Objects.requireNonNull(customerId, "customerId is required"), false);
    }

    public static Requester backOffice() {
        return new Requester(null, true);
    }

    /** May this requester see or change data that belongs to the given customer? */
    public boolean mayActFor(UUID ownerId) {
        return anyCustomer || (customerId != null && customerId.equals(ownerId));
    }
}

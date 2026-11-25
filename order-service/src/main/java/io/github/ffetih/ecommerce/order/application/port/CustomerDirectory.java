package io.github.ffetih.ecommerce.order.application.port;

import java.util.Optional;
import java.util.UUID;

/** What the order context needs to know about a customer, read from customer-service. */
public interface CustomerDirectory {

    record CustomerSnapshot(UUID id, boolean active, String shippingAddress) {
    }

    /**
     * @return the customer, or empty if customer-service does not know the id
     * @throws io.github.ffetih.ecommerce.order.application.DownstreamUnavailableException if it cannot be asked
     */
    Optional<CustomerSnapshot> find(UUID customerId);
}

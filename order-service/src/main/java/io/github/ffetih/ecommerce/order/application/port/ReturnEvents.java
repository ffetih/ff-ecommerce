package io.github.ffetih.ecommerce.order.application.port;

import io.github.ffetih.ecommerce.order.domain.Return;

/** Announces returns to the other contexts. Recorded in the caller's transaction (outbox). */
public interface ReturnEvents {

    /** The parcel is back: catalog puts its units in stock again. */
    void returnReceived(Return received);
}

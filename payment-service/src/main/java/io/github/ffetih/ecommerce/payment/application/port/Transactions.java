package io.github.ffetih.ecommerce.payment.application.port;

import java.util.function.Supplier;

/** Explicit transaction boundary for application services (ADR-0004). */
public interface Transactions {

    <T> T inTransaction(Supplier<T> work);
}

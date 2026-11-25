package io.github.ffetih.ecommerce.catalog.application.port;

import java.util.function.Supplier;

/**
 * Explicit transaction boundary for application services (ADR-0004).
 * Everything inside {@code work} commits or rolls back together; there are no nested
 * transactional proxies that could silently mark the transaction rollback-only.
 */
public interface Transactions {

    <T> T inTransaction(Supplier<T> work);
}

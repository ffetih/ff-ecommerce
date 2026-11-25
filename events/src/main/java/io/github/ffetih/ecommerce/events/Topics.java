package io.github.ffetih.ecommerce.events;

/**
 * Shared topic settings. Every service declares the topics it produces and consumes (creation is
 * idempotent), so they must agree on the partition count: a key's partition depends on it.
 */
public final class Topics {

    public static final int PARTITIONS = 3;

    private Topics() {
    }
}

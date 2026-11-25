package io.github.ffetih.ecommerce.order.adapter.in.scheduling;

import io.github.ffetih.ecommerce.order.application.OrderExpiry;
import io.github.ffetih.ecommerce.order.config.ExpiryProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs {@link OrderExpiry} on the real clock, every {@code scan-interval}. Nothing else: the rules
 * and their tests live in the application service.
 */
@Component
@ConditionalOnProperty(prefix = "ecommerce.orders.expiry", name = "enabled", havingValue = "true", matchIfMissing = true)
class ExpiryScheduler implements SmartLifecycle {

    private static final System.Logger log = System.getLogger(ExpiryScheduler.class.getName());

    private final OrderExpiry expiry;
    private final Clock clock;
    private final ExpiryProperties properties;
    private ScheduledExecutorService executor;

    ExpiryScheduler(OrderExpiry expiry, Clock clock, ExpiryProperties properties) {
        this.expiry = expiry;
        this.clock = clock;
        this.properties = properties;
    }

    @Override
    public synchronized void start() {
        executor = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("order-expiry").factory());
        long interval = properties.scanInterval().toMillis();
        executor.scheduleWithFixedDelay(this::scan, interval, interval, TimeUnit.MILLISECONDS);
    }

    /** Never lets an exception escape: a scheduled task that throws is never run again. */
    private void scan() {
        try {
            int expired = expiry.expireStale(clock.instant());
            if (expired > 0) {
                log.log(System.Logger.Level.INFO, "Expired {0} overdue order(s)", expired);
            }
        } catch (RuntimeException e) {
            log.log(System.Logger.Level.WARNING, "Order expiry scan failed; retrying at the next interval", e);
        }
    }

    @Override
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }
}

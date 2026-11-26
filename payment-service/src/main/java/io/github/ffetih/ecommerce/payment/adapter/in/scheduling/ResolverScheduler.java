package io.github.ffetih.ecommerce.payment.adapter.in.scheduling;

import io.github.ffetih.ecommerce.payment.application.PaymentService;
import io.github.ffetih.ecommerce.payment.application.RefundService;
import io.github.ffetih.ecommerce.payment.config.PaymentProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Runs the resolvers of payments and refunds on the real clock, every {@code scan-interval}. */
@Component
@ConditionalOnProperty(prefix = "ecommerce.payments.resolver", name = "enabled", havingValue = "true", matchIfMissing = true)
class ResolverScheduler implements SmartLifecycle {

    private static final System.Logger log = System.getLogger(ResolverScheduler.class.getName());

    private final PaymentService payments;
    private final RefundService refunds;
    private final Clock clock;
    private final PaymentProperties.Resolver properties;
    private ScheduledExecutorService executor;

    ResolverScheduler(PaymentService payments, RefundService refunds, Clock clock, PaymentProperties properties) {
        this.payments = payments;
        this.refunds = refunds;
        this.clock = clock;
        this.properties = properties.resolver();
    }

    @Override
    public synchronized void start() {
        executor = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("payment-resolver").factory());
        long interval = properties.scanInterval().toMillis();
        executor.scheduleWithFixedDelay(this::scan, interval, interval, TimeUnit.MILLISECONDS);
    }

    /** Never lets an exception escape: a scheduled task that throws is never run again. */
    private void scan() {
        try {
            int asked = payments.resolveInFlight(clock.instant()) + refunds.resolveInFlight(clock.instant());
            if (asked > 0) {
                log.log(System.Logger.Level.INFO, "Asked the provider again about {0} payment(s)", asked);
            }
        } catch (RuntimeException e) {
            log.log(System.Logger.Level.WARNING, "Payment resolver failed; retrying at the next interval", e);
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

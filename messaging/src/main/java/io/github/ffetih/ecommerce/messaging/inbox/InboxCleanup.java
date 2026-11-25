package io.github.ffetih.ecommerce.messaging.inbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Runs {@link Inbox#deleteExpired()} once an hour, so the inbox does not grow without bound. */
public class InboxCleanup implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(InboxCleanup.class);

    private final Inbox inbox;
    private volatile ScheduledExecutorService scheduler;

    public InboxCleanup(Inbox inbox) {
        this.inbox = inbox;
    }

    @Override
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("inbox-cleanup").factory());
        scheduler.scheduleWithFixedDelay(this::cleanUp, 5, 60, TimeUnit.MINUTES);
    }

    private void cleanUp() {
        try {
            int deleted = inbox.deleteExpired();
            if (deleted > 0) {
                log.info("Forgot {} processed event ids older than the inbox retention", deleted);
            }
        } catch (RuntimeException e) {
            // A task that throws is never scheduled again; log and try at the next run.
            log.error("Inbox cleanup failed; will retry", e);
        }
    }

    @Override
    public void stop() {
        ScheduledExecutorService current = scheduler;
        if (current != null) {
            current.shutdownNow();
            scheduler = null;
        }
    }

    @Override
    public boolean isRunning() {
        return scheduler != null;
    }
}

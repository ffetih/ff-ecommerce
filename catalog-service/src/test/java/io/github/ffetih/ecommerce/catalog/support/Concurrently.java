package io.github.ffetih.ecommerce.catalog.support;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/**
 * Runs tasks on virtual threads that all wait at a start gate and are released together,
 * to maximize real contention on the database.
 */
public final class Concurrently {

    public record Outcome(int succeeded, List<Throwable> failures) {
    }

    private Concurrently() {
    }

    public static Outcome run(int tasks, IntConsumer task) {
        var startGate = new CountDownLatch(1);
        var succeeded = new AtomicInteger();
        var failures = new ConcurrentLinkedQueue<Throwable>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < tasks; i++) {
                int taskNumber = i;
                executor.submit(() -> {
                    startGate.await();
                    try {
                        task.accept(taskNumber);
                        succeeded.incrementAndGet();
                    } catch (Throwable failure) {
                        failures.add(failure);
                    }
                    return null;
                });
            }
            startGate.countDown();
        } // close() waits until every task has finished

        return new Outcome(succeeded.get(), List.copyOf(failures));
    }
}

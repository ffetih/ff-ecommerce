package io.github.ffetih.ecommerce.messaging;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationView;
import io.micrometer.observation.transport.ReceiverContext;
import io.micrometer.observation.transport.SenderContext;

import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Just enough of a W3C tracer to test propagation without an OpenTelemetry SDK. Every observation gets a
 * trace id: from an incoming {@code traceparent} (receivers), else from its parent observation, else a new
 * one. Senders write {@code traceparent} into their carrier, as the real propagating handlers do.
 */
public class TestTracer implements ObservationHandler<Observation.Context> {

    private static final String TRACE_ID = TestTracer.class.getName() + ".traceId";

    /** The trace id of an observation that this tracer has started. */
    public static String traceIdOf(Observation observation) {
        return observation.getContext().get(TRACE_ID);
    }

    /** The trace id part of a W3C traceparent: {@code version-traceId-spanId-flags}. */
    public static String traceIdOf(String traceparent) {
        return traceparent.split("-")[1];
    }

    @Override
    public void onStart(Observation.Context context) {
        String traceId = incoming(context);
        if (traceId == null) {
            traceId = fromParent(context);
        }
        if (traceId == null) {
            traceId = randomHex(16);
        }
        context.put(TRACE_ID, traceId);
        if (context instanceof SenderContext<?> sender) {
            inject(sender, traceId);
        }
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return true;
    }

    private static String incoming(Observation.Context context) {
        return context instanceof ReceiverContext<?> receiver ? extract(receiver) : null;
    }

    private static <C> String extract(ReceiverContext<C> receiver) {
        C carrier = receiver.getCarrier();
        String traceparent = carrier == null ? null : receiver.getGetter().get(carrier, "traceparent");
        return traceparent == null ? null : traceIdOf(traceparent);
    }

    private static String fromParent(Observation.Context context) {
        ObservationView parent = context.getParentObservation();
        return parent == null ? null : parent.getContextView().get(TRACE_ID);
    }

    private static <C> void inject(SenderContext<C> sender, String traceId) {
        C carrier = sender.getCarrier();
        if (carrier != null) {
            sender.getSetter().set(carrier, "traceparent", "00-" + traceId + "-" + randomHex(8) + "-01");
        }
    }

    private static String randomHex(int bytes) {
        byte[] random = new byte[bytes];
        ThreadLocalRandom.current().nextBytes(random);
        return HexFormat.of().formatHex(random);
    }
}

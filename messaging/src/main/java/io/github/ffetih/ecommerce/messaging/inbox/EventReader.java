package io.github.ffetih.ecommerce.messaging.inbox;

import io.github.ffetih.ecommerce.messaging.EventEnvelope;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Parses Kafka record values into envelopes and their data, reporting unreadable input as malformed. */
public class EventReader {

    private final JsonMapper json;

    public EventReader(JsonMapper json) {
        this.json = json;
    }

    public EventEnvelope read(String value) {
        if (value == null || value.isBlank()) {
            throw new MalformedEventException("Empty message", null);
        }
        try {
            return json.readValue(value, EventEnvelope.class);
        } catch (JacksonException | IllegalArgumentException e) {
            throw new MalformedEventException("Not a valid event envelope: " + abbreviate(value), e);
        }
    }

    public <T> T data(EventEnvelope envelope, Class<T> type) {
        try {
            return json.treeToValue(envelope.data(), type);
        } catch (JacksonException | IllegalArgumentException e) {
            throw new MalformedEventException(
                    "Event " + envelope.eventId() + " (" + envelope.eventType() + ") has unreadable data", e);
        }
    }

    private static String abbreviate(String value) {
        return value.length() <= 200 ? value : value.substring(0, 200) + "...";
    }
}

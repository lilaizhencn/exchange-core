package exchange.core2.core.simulation.http;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable HTTP event encoded once before durable enqueue.
 *
 * <p>The exact payload bytes are retained so retries cannot change when a
 * newer application version uses a different JSON serializer.</p>
 */
public record EmporiaPortfolioHttpEvent(
        String eventId,
        String exchangeId,
        long deliveryId,
        long clientId,
        byte[] payload) {

    public EmporiaPortfolioHttpEvent {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId must not be blank");
        }
        if (exchangeId == null || exchangeId.isBlank()) {
            throw new IllegalArgumentException(
                    "exchangeId must not be blank");
        }
        if (deliveryId < 0) {
            throw new IllegalArgumentException(
                    "deliveryId must not be negative");
        }
        if (clientId <= 0) {
            throw new IllegalArgumentException("clientId must be positive");
        }
        Objects.requireNonNull(payload, "payload");
        if (payload.length == 0) {
            throw new IllegalArgumentException("payload must not be empty");
        }
        payload = Arrays.copyOf(payload, payload.length);
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }
}

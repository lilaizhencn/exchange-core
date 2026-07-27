package exchange.core2.core.simulation.http;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Connection and request settings for the Emporia HTTP portfolio adapter.
 *
 * @param baseUri         Emporia base URI, optionally including a path prefix
 * @param exchangeId      stable exchange instance identifier used in
 *                        idempotency keys
 * @param requestTimeout  timeout applied to every HTTP request
 * @param headers         transport headers such as {@code Authorization};
 *                        protocol-owned headers must not be supplied
 */
public record EmporiaHttpGatewayConfiguration(
        URI baseUri,
        String exchangeId,
        Duration requestTimeout,
        Map<String, String> headers) {

    private static final Duration DEFAULT_REQUEST_TIMEOUT =
            Duration.ofSeconds(3);

    public EmporiaHttpGatewayConfiguration {
        Objects.requireNonNull(baseUri, "baseUri");
        Objects.requireNonNull(exchangeId, "exchangeId");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        Objects.requireNonNull(headers, "headers");

        final String scheme = baseUri.getScheme();
        if (!baseUri.isAbsolute()
                || (!"http".equalsIgnoreCase(scheme)
                && !"https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException(
                    "baseUri must be an absolute HTTP or HTTPS URI");
        }
        if (baseUri.getHost() == null) {
            throw new IllegalArgumentException(
                    "baseUri must contain a host");
        }
        if (baseUri.getUserInfo() != null) {
            throw new IllegalArgumentException(
                    "baseUri must not contain user information");
        }
        if (baseUri.getQuery() != null || baseUri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "baseUri must not contain a query or fragment");
        }
        if (!exchangeId.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(
                    "exchangeId must contain only letters, digits, '.', '_', or '-'");
        }
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "requestTimeout must be positive");
        }

        headers.forEach((name, value) -> {
            if (name == null || name.isBlank()
                    || value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                        "HTTP header names and values must not be blank");
            }
            if (isProtocolHeader(name)) {
                throw new IllegalArgumentException(
                        "header is managed by the Emporia adapter: " + name);
            }
        });
        headers = Map.copyOf(headers);
    }

    public static EmporiaHttpGatewayConfiguration create(
            final URI baseUri,
            final String exchangeId) {
        return new EmporiaHttpGatewayConfiguration(
                baseUri,
                exchangeId,
                DEFAULT_REQUEST_TIMEOUT,
                Map.of());
    }

    private static boolean isProtocolHeader(final String name) {
        return name.equalsIgnoreCase("Accept")
                || name.equalsIgnoreCase("Content-Type")
                || name.equalsIgnoreCase("Idempotency-Key");
    }
}

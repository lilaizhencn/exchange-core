package exchange.core2.core.simulation.http;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import exchange.core2.core.simulation.EmporiaPortfolioGateway;
import exchange.core2.core.simulation.EmporiaPortfolioSeed;
import exchange.core2.core.simulation.EmporiaPortfolioSnapshot;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

/**
 * Asynchronous HTTP implementation of the Emporia portfolio gateway.
 *
 * <p>The adapter uses these endpoints relative to the configured base URI:</p>
 * <ul>
 *     <li>{@code GET internal/v1/portfolios/{clientId}/risk-seed}</li>
 *     <li>{@code PUT internal/v1/portfolio-snapshots/{deliveryId}/{clientId}}</li>
 * </ul>
 *
 * <p>Snapshot requests carry an {@code Idempotency-Key} formatted as
 * {@code exchangeId:deliveryId:clientId}. Emporia must treat a repeated key
 * with the same body as a successful duplicate and reject a repeated key with
 * a different body.</p>
 */
public class HttpEmporiaPortfolioGateway
        implements EmporiaPortfolioGateway {

    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_ERROR_BODY_LENGTH = 1_024;
    private static final String JSON_CONTENT_TYPE = "application/json";

    private final EmporiaHttpGatewayConfiguration configuration;
    private final HttpClient client;
    private final ObjectMapper objectMapper;
    private final URI endpointBaseUri;

    public HttpEmporiaPortfolioGateway(
            final EmporiaHttpGatewayConfiguration configuration) {
        this(
                configuration,
                HttpClient.newBuilder()
                        .connectTimeout(configuration.requestTimeout())
                        .build(),
                defaultObjectMapper());
    }

    HttpEmporiaPortfolioGateway(
            final EmporiaHttpGatewayConfiguration configuration,
            final HttpClient client,
            final ObjectMapper objectMapper) {
        this.configuration =
                Objects.requireNonNull(configuration, "configuration");
        this.client = Objects.requireNonNull(client, "client");
        this.objectMapper =
                Objects.requireNonNull(objectMapper, "objectMapper");
        endpointBaseUri = normalizeBaseUri(configuration.baseUri());
    }

    @Override
    public CompletableFuture<EmporiaPortfolioSeed> load(
            final long clientId) {
        if (clientId <= 0) {
            throw new IllegalArgumentException("clientId must be positive");
        }

        final URI endpoint = endpointBaseUri.resolve(
                "internal/v1/portfolios/"
                        + clientId
                        + "/risk-seed");
        final HttpRequest request = request(endpoint)
                .GET()
                .build();

        return send(
                request,
                "load portfolio " + clientId,
                response -> decodeSeed(clientId, response));
    }

    @Override
    public CompletableFuture<Void> publish(
            final EmporiaPortfolioSnapshot snapshot) {
        return publishEncoded(encode(snapshot));
    }

    /**
     * Encodes a snapshot once for direct delivery or durable outbox storage.
     */
    public EmporiaPortfolioHttpEvent encode(
            final EmporiaPortfolioSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        final String idempotencyKey =
                configuration.exchangeId()
                        + ":"
                        + snapshot.deliveryId()
                        + ":"
                        + snapshot.clientId();
        final byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(
                    SnapshotRequest.from(
                            configuration.exchangeId(),
                            snapshot));
        } catch (final IOException error) {
            throw protocolFailure(
                    "encode portfolio snapshot",
                    error);
        }

        return new EmporiaPortfolioHttpEvent(
                idempotencyKey,
                configuration.exchangeId(),
                snapshot.deliveryId(),
                snapshot.clientId(),
                body);
    }

    /**
     * Sends an already encoded event without regenerating its payload.
     */
    public CompletableFuture<Void> publishEncoded(
            final EmporiaPortfolioHttpEvent event) {
        Objects.requireNonNull(event, "event");
        final URI endpoint = endpointBaseUri.resolve(
                "internal/v1/portfolio-snapshots/"
                        + event.deliveryId()
                        + "/"
                        + event.clientId());
        final HttpRequest request = request(endpoint)
                .header("Content-Type", JSON_CONTENT_TYPE)
                .header("Idempotency-Key", event.eventId())
                .PUT(HttpRequest.BodyPublishers.ofByteArray(
                        event.payload()))
                .build();

        return send(
                request,
                "publish portfolio "
                        + event.clientId()
                        + " delivery "
                        + event.deliveryId(),
                response -> null);
    }

    private HttpRequest.Builder request(final URI endpoint) {
        final HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(endpoint)
                .timeout(configuration.requestTimeout())
                .header("Accept", JSON_CONTENT_TYPE);
        configuration.headers().forEach(request::header);
        return request;
    }

    private <T> CompletableFuture<T> send(
            final HttpRequest request,
            final String operation,
            final Function<HttpResponse<byte[]>, T> decoder) {
        return client.sendAsync(
                        request,
                        HttpResponse.BodyHandlers.ofByteArray())
                .handle((response, error) -> {
                    if (error != null) {
                        throw transportFailure(operation, unwrap(error));
                    }
                    requireSuccess(operation, response);
                    return decoder.apply(response);
                });
    }

    private EmporiaPortfolioSeed decodeSeed(
            final long requestedClientId,
            final HttpResponse<byte[]> response) {
        requireJsonResponse(response);

        final SeedResponse seed;
        try {
            seed = objectMapper.readValue(
                    response.body(),
                    SeedResponse.class);
        } catch (final IOException error) {
            throw protocolFailure(
                    "decode portfolio " + requestedClientId,
                    error);
        }
        if (seed.schemaVersion() == null
                || seed.schemaVersion() != SCHEMA_VERSION) {
            throw protocolFailure(
                    "portfolio response has an unsupported schema version",
                    null);
        }
        if (seed.clientId() == null
                || seed.clientId() != requestedClientId) {
            throw protocolFailure(
                    "portfolio response client ID does not match request",
                    null);
        }
        if (seed.firstTransactionId() == null) {
            throw protocolFailure(
                    "portfolio response is missing firstTransactionId",
                    null);
        }
        if (seed.balances() == null) {
            throw protocolFailure(
                    "portfolio response is missing balances",
                    null);
        }

        final Map<Integer, Long> balances = new HashMap<>();
        for (final Balance balance : seed.balances()) {
            if (balance == null) {
                throw protocolFailure(
                        "portfolio response contains a null balance",
                        null);
            }
            if (balance.assetId() == null
                    || balance.amount() == null) {
                throw protocolFailure(
                        "portfolio response contains an incomplete balance",
                        null);
            }
            final Long previous =
                    balances.put(balance.assetId(), balance.amount());
            if (previous != null) {
                throw protocolFailure(
                        "portfolio response contains duplicate asset "
                                + balance.assetId(),
                        null);
            }
        }
        try {
            return new EmporiaPortfolioSeed(
                    seed.clientId(),
                    seed.firstTransactionId(),
                    balances);
        } catch (final IllegalArgumentException
                       | ArithmeticException error) {
            throw protocolFailure(
                    "portfolio response violates the risk-seed contract",
                    error);
        }
    }

    private static void requireSuccess(
            final String operation,
            final HttpResponse<byte[]> response) {
        final int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return;
        }

        final String responseBody = limitedBody(response.body());
        final String detail = responseBody.isBlank()
                ? ""
                : ": " + responseBody;
        throw new EmporiaHttpException(
                operation + " failed with HTTP " + status + detail,
                status,
                status == 408 || status == 429 || status >= 500,
                null);
    }

    private static void requireJsonResponse(
            final HttpResponse<byte[]> response) {
        final String contentType = response.headers()
                .firstValue("Content-Type")
                .orElse("");
        if (!contentType.toLowerCase(Locale.ROOT)
                .startsWith(JSON_CONTENT_TYPE)) {
            throw protocolFailure(
                    "portfolio response Content-Type is not application/json",
                    null);
        }
    }

    private static EmporiaHttpException transportFailure(
            final String operation,
            final Throwable cause) {
        return new EmporiaHttpException(
                operation + " failed before receiving a valid HTTP response",
                null,
                true,
                cause);
    }

    private static EmporiaHttpException protocolFailure(
            final String message,
            final Throwable cause) {
        return new EmporiaHttpException(
                message,
                null,
                false,
                cause);
    }

    private static Throwable unwrap(final Throwable error) {
        return error instanceof CompletionException
                && error.getCause() != null
                ? error.getCause()
                : error;
    }

    private static String limitedBody(final byte[] responseBody) {
        if (responseBody == null || responseBody.length == 0) {
            return "";
        }
        final String body = new String(
                responseBody,
                StandardCharsets.UTF_8);
        return body.length() <= MAX_ERROR_BODY_LENGTH
                ? body
                : body.substring(0, MAX_ERROR_BODY_LENGTH);
    }

    private static URI normalizeBaseUri(final URI baseUri) {
        final String value = baseUri.toString();
        return URI.create(value.endsWith("/") ? value : value + "/");
    }

    private static ObjectMapper defaultObjectMapper() {
        return new ObjectMapper()
                .disable(
                        DeserializationFeature
                                .FAIL_ON_UNKNOWN_PROPERTIES);
    }

    private record Balance(
            Integer assetId,
            Long amount) {
    }

    private record SeedResponse(
            Integer schemaVersion,
            Long clientId,
            Long firstTransactionId,
            List<Balance> balances) {
    }

    private record SnapshotRequest(
            int schemaVersion,
            String exchangeId,
            long deliveryId,
            long clientId,
            List<Balance> availableBalances) {

        private static SnapshotRequest from(
                final String exchangeId,
                final EmporiaPortfolioSnapshot snapshot) {
            final List<Balance> balances =
                    new ArrayList<>(snapshot.availableBalances().size());
            snapshot.availableBalances().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> balances.add(
                            new Balance(
                                    entry.getKey(),
                                    entry.getValue())));
            return new SnapshotRequest(
                    SCHEMA_VERSION,
                    exchangeId,
                    snapshot.deliveryId(),
                    snapshot.clientId(),
                    List.copyOf(balances));
        }
    }
}

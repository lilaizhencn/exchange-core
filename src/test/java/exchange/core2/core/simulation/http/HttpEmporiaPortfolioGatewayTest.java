package exchange.core2.core.simulation.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import exchange.core2.core.simulation.EmporiaPortfolioSeed;
import exchange.core2.core.simulation.EmporiaPortfolioSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class HttpEmporiaPortfolioGatewayTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private HttpServer server;
    private ExecutorService serverExecutor;
    private URI adapterBaseUri;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0);
        serverExecutor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(serverExecutor);
        server.start();
        adapterBaseUri = URI.create(
                "http://127.0.0.1:"
                        + server.getAddress().getPort()
                        + "/adapter");
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void shouldLoadAndValidatePortfolioSeed() {
        final AtomicReference<String> method = new AtomicReference<>();
        final AtomicReference<String> authorization =
                new AtomicReference<>();
        server.createContext(
                "/adapter/internal/v1/portfolios/101/risk-seed",
                exchange -> {
                    method.set(exchange.getRequestMethod());
                    authorization.set(exchange.getRequestHeaders()
                            .getFirst("Authorization"));
                    respond(
                            exchange,
                            200,
                            "application/json; charset=utf-8",
                            """
                            {
                              "schemaVersion": 1,
                              "clientId": 101,
                              "firstTransactionId": 7001,
                              "balances": [
                                {"assetId": 840, "amount": 50000},
                                {"assetId": 20001, "amount": 12}
                              ],
                              "portfolioName": "primary"
                            }
                            """);
                });

        final HttpEmporiaPortfolioGateway gateway = gateway(
                Duration.ofSeconds(1),
                Map.of("Authorization", "Bearer test-token"));
        final EmporiaPortfolioSeed seed = gateway.load(101).join();

        assertEquals("GET", method.get());
        assertEquals("Bearer test-token", authorization.get());
        assertEquals(101, seed.clientId());
        assertEquals(7001, seed.firstTransactionId());
        assertEquals(
                Map.of(840, 50_000L, 20_001, 12L),
                seed.balances());
    }

    @Test
    void shouldPublishDeterministicIdempotentSnapshot() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final List<String> requestBodies = new CopyOnWriteArrayList<>();
        final List<String> idempotencyKeys =
                new CopyOnWriteArrayList<>();
        server.createContext(
                "/adapter/internal/v1/portfolio-snapshots/13/101",
                exchange -> {
                    calls.incrementAndGet();
                    requestBodies.add(new String(
                            exchange.getRequestBody().readAllBytes(),
                            StandardCharsets.UTF_8));
                    idempotencyKeys.add(exchange.getRequestHeaders()
                            .getFirst("Idempotency-Key"));
                    assertEquals(
                            "application/json",
                            exchange.getRequestHeaders()
                                    .getFirst("Content-Type"));
                    respond(exchange, 204, null, "");
                });

        final HttpEmporiaPortfolioGateway gateway =
                gateway(Duration.ofSeconds(1), Map.of());
        final EmporiaPortfolioSnapshot snapshot =
                new EmporiaPortfolioSnapshot(
                        13,
                        101,
                        Map.of(20_001, 5L, 840, 0L));

        gateway.publish(snapshot).join();
        gateway.publish(snapshot).join();

        assertEquals(2, calls.get());
        assertEquals(
                List.of("exchange-1:13:101", "exchange-1:13:101"),
                idempotencyKeys);
        assertEquals(requestBodies.get(0), requestBodies.get(1));

        final JsonNode body =
                objectMapper.readTree(requestBodies.get(0));
        assertEquals(1, body.get("schemaVersion").asInt());
        assertEquals("exchange-1", body.get("exchangeId").asText());
        assertEquals(13, body.get("deliveryId").asLong());
        assertEquals(101, body.get("clientId").asLong());
        assertEquals(
                840,
                body.get("availableBalances")
                        .get(0)
                        .get("assetId")
                        .asInt());
        assertEquals(
                20_001,
                body.get("availableBalances")
                        .get(1)
                        .get("assetId")
                        .asInt());
    }

    @Test
    void shouldClassifyHttpFailuresForCallerRetry() {
        server.createContext(
                "/adapter/internal/v1/portfolios/501/risk-seed",
                exchange -> respond(
                        exchange,
                        503,
                        "text/plain",
                        "portfolio service unavailable"));
        server.createContext(
                "/adapter/internal/v1/portfolios/404/risk-seed",
                exchange -> respond(
                        exchange,
                        404,
                        "text/plain",
                        "portfolio not found"));

        final HttpEmporiaPortfolioGateway gateway =
                gateway(Duration.ofSeconds(1), Map.of());
        final EmporiaHttpException unavailable =
                failureOf(() -> gateway.load(501).join());
        final EmporiaHttpException notFound =
                failureOf(() -> gateway.load(404).join());

        assertEquals(503, unavailable.statusCode());
        assertTrue(unavailable.retryable());
        assertTrue(unavailable.getMessage()
                .contains("portfolio service unavailable"));
        assertEquals(404, notFound.statusCode());
        assertFalse(notFound.retryable());
    }

    @Test
    void shouldRejectMalformedOrMismatchedSeedResponses() {
        server.createContext(
                "/adapter/internal/v1/portfolios/101/risk-seed",
                exchange -> respond(
                        exchange,
                        200,
                        "application/json",
                        """
                        {
                          "schemaVersion": 1,
                          "clientId": 999,
                          "firstTransactionId": 1,
                          "balances": []
                        }
                        """));
        server.createContext(
                "/adapter/internal/v1/portfolios/102/risk-seed",
                exchange -> respond(
                        exchange,
                        200,
                        "application/json",
                        """
                        {
                          "schemaVersion": 1,
                          "clientId": 102,
                          "firstTransactionId": 1,
                          "balances": [
                            {"assetId": 840, "amount": 10},
                            {"assetId": 840, "amount": 10}
                          ]
                        }
                        """));
        server.createContext(
                "/adapter/internal/v1/portfolios/103/risk-seed",
                exchange -> respond(
                        exchange,
                        200,
                        "text/plain",
                        "{}"));
        server.createContext(
                "/adapter/internal/v1/portfolios/104/risk-seed",
                exchange -> respond(
                        exchange,
                        200,
                        "application/json",
                        """
                        {
                          "schemaVersion": 2,
                          "clientId": 104,
                          "firstTransactionId": 1,
                          "balances": []
                        }
                        """));

        final HttpEmporiaPortfolioGateway gateway =
                gateway(Duration.ofSeconds(1), Map.of());

        assertFalse(failureOf(() -> gateway.load(101).join())
                .retryable());
        assertFalse(failureOf(() -> gateway.load(102).join())
                .retryable());
        assertFalse(failureOf(() -> gateway.load(103).join())
                .retryable());
        assertFalse(failureOf(() -> gateway.load(104).join())
                .retryable());
    }

    @Test
    void shouldApplyRequestTimeoutWithoutBlockingTheCaller() {
        final CountDownLatch requestReceived = new CountDownLatch(1);
        final CountDownLatch releaseServer = new CountDownLatch(1);
        server.createContext(
                "/adapter/internal/v1/portfolios/101/risk-seed",
                exchange -> {
                    requestReceived.countDown();
                    try {
                        releaseServer.await(2, TimeUnit.SECONDS);
                    } catch (final InterruptedException error) {
                        Thread.currentThread().interrupt();
                    }
                    exchange.close();
                });

        final HttpEmporiaPortfolioGateway gateway =
                gateway(Duration.ofMillis(100), Map.of());
        try {
            final EmporiaHttpException timeout =
                    failureOf(() -> gateway.load(101).join());
            assertTrue(timeout.retryable());
            assertTrue(timeout.getCause() instanceof HttpTimeoutException);
            assertTrue(requestReceived.await(1, TimeUnit.SECONDS));
        } catch (final InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        } finally {
            releaseServer.countDown();
        }
    }

    @Test
    void shouldRejectUnsafeConfiguration() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new EmporiaHttpGatewayConfiguration(
                        adapterBaseUri,
                        "exchange:1",
                        Duration.ofSeconds(1),
                        Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new EmporiaHttpGatewayConfiguration(
                        adapterBaseUri,
                        "exchange-1",
                        Duration.ofSeconds(1),
                        Map.of("Idempotency-Key", "caller-value")));
    }

    private HttpEmporiaPortfolioGateway gateway(
            final Duration timeout,
            final Map<String, String> headers) {
        return new HttpEmporiaPortfolioGateway(
                new EmporiaHttpGatewayConfiguration(
                        adapterBaseUri,
                        "exchange-1",
                        timeout,
                        headers));
    }

    private static EmporiaHttpException failureOf(
            final Runnable operation) {
        final CompletionException failure =
                assertThrows(CompletionException.class, operation::run);
        assertTrue(failure.getCause() instanceof EmporiaHttpException);
        return (EmporiaHttpException) failure.getCause();
    }

    private static void respond(
            final HttpExchange exchange,
            final int status,
            final String contentType,
            final String responseBody) throws IOException {
        if (contentType != null) {
            exchange.getResponseHeaders()
                    .set("Content-Type", contentType);
        }
        final byte[] body =
                responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(
                status,
                body.length == 0 ? -1 : body.length);
        if (body.length != 0) {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }
}

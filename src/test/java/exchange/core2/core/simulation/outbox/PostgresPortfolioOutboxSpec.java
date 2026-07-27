package exchange.core2.core.simulation.outbox;

import exchange.core2.core.simulation.http.EmporiaPortfolioHttpEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
public class PostgresPortfolioOutboxSpec {

    @Container
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine");

    private DataSource dataSource;
    private PostgresPortfolioOutbox outbox;

    @BeforeEach
    void resetDatabase() throws SQLException, IOException {
        final PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(postgres.getJdbcUrl());
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        dataSource = source;
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(
                    """
                    DROP TABLE IF EXISTS
                        exchange_core_portfolio_outbox
                    """);
            statement.execute(migration());
        }
        outbox = new PostgresPortfolioOutbox(dataSource);
    }

    @Test
    void enqueueIsIdempotentAndRejectsChangedPayload() {
        final EmporiaPortfolioHttpEvent event =
                event(13, 101, "first");
        outbox.enqueue(event);
        outbox.enqueue(event);

        assertThrows(
                PortfolioOutboxException.class,
                () -> outbox.enqueue(
                        event(13, 101, "different")));
    }

    @Test
    void blocksLaterClientEventUntilEarlierEventCompletes() {
        outbox.enqueue(event(13, 101, "first"));
        outbox.enqueue(event(14, 101, "second"));
        outbox.enqueue(event(15, 102, "other-client"));
        final Instant now = Instant.now().plusSeconds(1);

        final List<PortfolioOutboxRecord> first =
                outbox.claim(
                        "worker-1",
                        10,
                        now,
                        Duration.ofSeconds(30));
        assertEquals(
                List.of(13L, 15L),
                first.stream()
                        .map(record ->
                                record.event().deliveryId())
                        .sorted()
                        .toList());

        first.forEach(record -> outbox.markPublished(
                record.event().eventId(),
                "worker-1",
                now));
        final List<PortfolioOutboxRecord> second =
                outbox.claim(
                        "worker-2",
                        10,
                        now,
                        Duration.ofSeconds(30));
        assertEquals(1, second.size());
        assertEquals(14, second.getFirst().event().deliveryId());
    }

    @Test
    void expiredLeaseIsRecoveredByAnotherWorker() {
        outbox.enqueue(event(13, 101, "first"));
        final Instant now = Instant.now().plusSeconds(1);
        assertEquals(
                1,
                outbox.claim(
                                "crashed-worker",
                                1,
                                now,
                                Duration.ofSeconds(1))
                        .size());

        final List<PortfolioOutboxRecord> recovered =
                outbox.claim(
                        "replacement-worker",
                        1,
                        now.plusSeconds(2),
                        Duration.ofSeconds(30));
        assertEquals(1, recovered.size());
        assertEquals(2, recovered.getFirst().attemptCount());
    }

    private static EmporiaPortfolioHttpEvent event(
            final long deliveryId,
            final long clientId,
            final String payload) {
        return new EmporiaPortfolioHttpEvent(
                "exchange-1:" + deliveryId + ":" + clientId,
                "exchange-1",
                deliveryId,
                clientId,
                payload.getBytes(StandardCharsets.UTF_8));
    }

    private static String migration() throws IOException {
        try (var input = PostgresPortfolioOutboxSpec.class
                .getResourceAsStream(
                        "/db/portfolio-outbox/"
                                + "V1__create_portfolio_outbox.sql")) {
            assertTrue(input != null);
            return new String(
                    input.readAllBytes(),
                    StandardCharsets.UTF_8);
        }
    }
}

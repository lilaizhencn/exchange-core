package exchange.core2.core.simulation.outbox;

import exchange.core2.core.simulation.EmporiaPortfolioChange;
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
            statement.execute(migration("V1__create_portfolio_outbox.sql"));
            statement.execute(migration("V2__portfolio_outbox_change_kind.sql"));
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

    @Test
    void supersedesThisClientsEarlierUndeliveredReservations() {
        outbox.enqueue(event(1, 101, "hold-1", EmporiaPortfolioChange.RESERVED));
        outbox.enqueue(event(2, 101, "hold-2", EmporiaPortfolioChange.RESERVED));
        outbox.enqueue(event(3, 101, "hold-3", EmporiaPortfolioChange.RESERVED));

        assertEquals(1, countByStatus(101, "PENDING"));
        assertEquals(2, countByStatus(101, "SUPERSEDED"));

        final List<PortfolioOutboxRecord> claimed = outbox.claim(
                "worker-1", 10, Instant.now(), Duration.ofSeconds(30));
        assertEquals(1, claimed.size());
        assertEquals("exchange-1:3:101", claimed.getFirst().event().eventId());
    }

    @Test
    void neverSupersedesSettledChanges() {
        outbox.enqueue(event(1, 101, "fill-1", EmporiaPortfolioChange.SETTLED));
        outbox.enqueue(event(2, 101, "fill-2", EmporiaPortfolioChange.SETTLED));
        outbox.enqueue(event(3, 101, "fill-3", EmporiaPortfolioChange.SETTLED));

        // Each settled change is its own audit record and has to be delivered
        // and acknowledged on its own.
        assertEquals(3, countByStatus(101, "PENDING"));
        assertEquals(0, countByStatus(101, "SUPERSEDED"));
    }

    @Test
    void aReservationDoesNotSupersedeAnUndeliveredSettledChange() {
        outbox.enqueue(event(1, 101, "fill", EmporiaPortfolioChange.SETTLED));
        outbox.enqueue(event(2, 101, "hold", EmporiaPortfolioChange.RESERVED));

        assertEquals(2, countByStatus(101, "PENDING"));
        assertEquals(0, countByStatus(101, "SUPERSEDED"));

        // Order across the two kinds still holds: both carry the whole balance,
        // so the settled one must not be overtaken by the later reservation.
        final List<PortfolioOutboxRecord> claimed = outbox.claim(
                "worker-1", 10, Instant.now(), Duration.ofSeconds(30));
        assertEquals(1, claimed.size());
        assertEquals("exchange-1:1:101", claimed.getFirst().event().eventId());
    }

    @Test
    void supersedingIsScopedToOneClient() {
        outbox.enqueue(event(1, 101, "hold-a", EmporiaPortfolioChange.RESERVED));
        outbox.enqueue(event(2, 202, "hold-b", EmporiaPortfolioChange.RESERVED));
        outbox.enqueue(event(3, 101, "hold-c", EmporiaPortfolioChange.RESERVED));

        assertEquals(1, countByStatus(101, "PENDING"));
        assertEquals(1, countByStatus(202, "PENDING"));
        assertEquals(1, countByStatus(101, "SUPERSEDED"));
        assertEquals(0, countByStatus(202, "SUPERSEDED"));
    }

    @Test
    void doesNotRevivePublishedReservations() {
        outbox.enqueue(event(1, 101, "hold-1", EmporiaPortfolioChange.RESERVED));
        final List<PortfolioOutboxRecord> claimed = outbox.claim(
                "worker-1", 10, Instant.now(), Duration.ofSeconds(30));
        outbox.markPublished(claimed.getFirst().event().eventId(),
                "worker-1", Instant.now());

        outbox.enqueue(event(2, 101, "hold-2", EmporiaPortfolioChange.RESERVED));

        assertEquals(1, countByStatus(101, "PENDING"));
        assertEquals(1, countByStatus(101, "PUBLISHED"));
        assertEquals(0, countByStatus(101, "SUPERSEDED"));
    }

    private int countByStatus(final long clientId, final String status) {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     """
                     SELECT count(*) FROM exchange_core_portfolio_outbox
                      WHERE client_id = ? AND status = ?
                     """)) {
            statement.setLong(1, clientId);
            statement.setString(2, status);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        } catch (final SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static EmporiaPortfolioHttpEvent event(
            final long deliveryId,
            final long clientId,
            final String payload) {
        return event(deliveryId, clientId, payload,
                EmporiaPortfolioChange.SETTLED);
    }

    private static EmporiaPortfolioHttpEvent event(
            final long deliveryId,
            final long clientId,
            final String payload,
            final EmporiaPortfolioChange change) {
        return new EmporiaPortfolioHttpEvent(
                "exchange-1:" + deliveryId + ":" + clientId,
                "exchange-1",
                deliveryId,
                clientId,
                change,
                payload.getBytes(StandardCharsets.UTF_8));
    }

    private static String migration(final String file) throws IOException {
        try (var input = PostgresPortfolioOutboxSpec.class
                .getResourceAsStream(
                        "/db/portfolio-outbox/" + file)) {
            assertTrue(input != null);
            return new String(
                    input.readAllBytes(),
                    StandardCharsets.UTF_8);
        }
    }
}

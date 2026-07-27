package exchange.core2.core.simulation.outbox;

import exchange.core2.core.simulation.http.EmporiaPortfolioHttpEvent;

import javax.sql.DataSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * PostgreSQL implementation of the portfolio outbox.
 */
public final class PostgresPortfolioOutbox
        implements PortfolioOutboxStore {

    private static final int SCHEMA_VERSION = 1;

    private final DataSource dataSource;

    public PostgresPortfolioOutbox(final DataSource dataSource) {
        this.dataSource =
                Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void enqueue(final EmporiaPortfolioHttpEvent event) {
        Objects.requireNonNull(event, "event");
        final byte[] payload = event.payload();
        final String digest = sha256(payload);

        inTransaction(connection -> {
            final int inserted;
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO exchange_core_portfolio_outbox (
                        event_id,
                        exchange_id,
                        delivery_id,
                        client_id,
                        schema_version,
                        payload,
                        payload_sha256
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (event_id) DO NOTHING
                    """)) {
                statement.setString(1, event.eventId());
                statement.setString(2, event.exchangeId());
                statement.setLong(3, event.deliveryId());
                statement.setLong(4, event.clientId());
                statement.setInt(5, SCHEMA_VERSION);
                statement.setBytes(6, payload);
                statement.setString(7, digest);
                inserted = statement.executeUpdate();
            }
            if (inserted == 0) {
                verifyDuplicate(connection, event, payload, digest);
            }
            return null;
        });
    }

    @Override
    public List<PortfolioOutboxRecord> claim(
            final String workerId,
            final int batchSize,
            final Instant now,
            final Duration leaseDuration) {
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        final Instant leaseUntil = now.plus(leaseDuration);

        return inTransaction(connection -> {
            final List<PortfolioOutboxRecord> records =
                    new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    WITH candidates AS (
                        SELECT candidate.event_id
                        FROM exchange_core_portfolio_outbox candidate
                        WHERE (
                            (
                                candidate.status IN ('PENDING', 'RETRY')
                                AND candidate.next_attempt_at <= ?
                            )
                            OR (
                                candidate.status = 'IN_FLIGHT'
                                AND candidate.lease_until <= ?
                            )
                        )
                        AND NOT EXISTS (
                            SELECT 1
                            FROM exchange_core_portfolio_outbox earlier
                            WHERE earlier.client_id = candidate.client_id
                              AND earlier.sequence_id < candidate.sequence_id
                              AND earlier.status IN (
                                  'PENDING',
                                  'RETRY',
                                  'IN_FLIGHT'
                              )
                        )
                        ORDER BY candidate.sequence_id
                        FOR UPDATE OF candidate SKIP LOCKED
                        LIMIT ?
                    )
                    UPDATE exchange_core_portfolio_outbox claimed
                    SET status = 'IN_FLIGHT',
                        lease_owner = ?,
                        lease_until = ?,
                        attempt_count = claimed.attempt_count + 1
                    FROM candidates
                    WHERE claimed.event_id = candidates.event_id
                    RETURNING
                        claimed.sequence_id,
                        claimed.event_id,
                        claimed.exchange_id,
                        claimed.delivery_id,
                        claimed.client_id,
                        claimed.payload,
                        claimed.attempt_count
                    """)) {
                statement.setTimestamp(1, Timestamp.from(now));
                statement.setTimestamp(2, Timestamp.from(now));
                statement.setInt(3, batchSize);
                statement.setString(4, workerId);
                statement.setTimestamp(5, Timestamp.from(leaseUntil));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        records.add(mapRecord(result));
                    }
                }
            }
            return List.copyOf(records);
        });
    }

    @Override
    public void markPublished(
            final String eventId,
            final String workerId,
            final Instant publishedAt) {
        updateLease(
                eventId,
                workerId,
                """
                UPDATE exchange_core_portfolio_outbox
                SET status = 'PUBLISHED',
                    published_at = ?,
                    lease_owner = NULL,
                    lease_until = NULL,
                    last_error = NULL
                WHERE event_id = ?
                  AND status = 'IN_FLIGHT'
                  AND lease_owner = ?
                """,
                2,
                statement -> statement.setTimestamp(
                        1,
                        Timestamp.from(publishedAt)));
    }

    @Override
    public void markRetry(
            final String eventId,
            final String workerId,
            final Instant nextAttemptAt,
            final String error) {
        updateLease(
                eventId,
                workerId,
                """
                UPDATE exchange_core_portfolio_outbox
                SET status = 'RETRY',
                    next_attempt_at = ?,
                    lease_owner = NULL,
                    lease_until = NULL,
                    last_error = ?
                WHERE event_id = ?
                  AND status = 'IN_FLIGHT'
                  AND lease_owner = ?
                """,
                3,
                statement -> {
                    statement.setTimestamp(
                            1,
                            Timestamp.from(nextAttemptAt));
                    statement.setString(2, error);
                });
    }

    @Override
    public void markDead(
            final String eventId,
            final String workerId,
            final String error) {
        updateLease(
                eventId,
                workerId,
                """
                UPDATE exchange_core_portfolio_outbox
                SET status = 'DEAD',
                    lease_owner = NULL,
                    lease_until = NULL,
                    last_error = ?
                WHERE event_id = ?
                  AND status = 'IN_FLIGHT'
                  AND lease_owner = ?
                """,
                2,
                statement -> statement.setString(1, error));
    }

    private void verifyDuplicate(
            final Connection connection,
            final EmporiaPortfolioHttpEvent event,
            final byte[] payload,
            final String digest) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT
                    exchange_id,
                    delivery_id,
                    client_id,
                    schema_version,
                    payload,
                    payload_sha256
                FROM exchange_core_portfolio_outbox
                WHERE event_id = ?
                """)) {
            statement.setString(1, event.eventId());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new PortfolioOutboxException(
                            "conflicting outbox event disappeared");
                }
                final boolean identical =
                        event.exchangeId().equals(result.getString(1))
                                && event.deliveryId() == result.getLong(2)
                                && event.clientId() == result.getLong(3)
                                && result.getInt(4) == SCHEMA_VERSION
                                && Arrays.equals(
                                        payload,
                                        result.getBytes(5))
                                && digest.equals(result.getString(6));
                if (!identical) {
                    throw new PortfolioOutboxException(
                            "event ID was reused with a different payload: "
                                    + event.eventId());
                }
            }
        }
    }

    private PortfolioOutboxRecord mapRecord(
            final ResultSet result) throws SQLException {
        return new PortfolioOutboxRecord(
                result.getLong("sequence_id"),
                new EmporiaPortfolioHttpEvent(
                        result.getString("event_id"),
                        result.getString("exchange_id"),
                        result.getLong("delivery_id"),
                        result.getLong("client_id"),
                        result.getBytes("payload")),
                result.getInt("attempt_count"));
    }

    private void updateLease(
            final String eventId,
            final String workerId,
            final String sql,
            final int eventParameterIndex,
            final StatementParameters parameters) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(workerId, "workerId");
        try (Connection connection = dataSource.getConnection();
            PreparedStatement statement =
                     connection.prepareStatement(sql)) {
            parameters.apply(statement);
            statement.setString(eventParameterIndex, eventId);
            statement.setString(
                    eventParameterIndex + 1,
                    workerId);
            final int updated = statement.executeUpdate();
            if (updated != 1) {
                throw new PortfolioOutboxException(
                        "outbox lease is no longer owned for event "
                                + eventId);
            }
        } catch (final SQLException error) {
            throw new PortfolioOutboxException(
                    "could not update outbox event " + eventId,
                    error);
        }
    }

    private <T> T inTransaction(
            final TransactionWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                final T result = work.apply(connection);
                connection.commit();
                return result;
            } catch (final SQLException | RuntimeException error) {
                rollback(connection, error);
                throw error;
            }
        } catch (final SQLException error) {
            throw new PortfolioOutboxException(
                    "portfolio outbox transaction failed",
                    error);
        }
    }

    private static void rollback(
            final Connection connection,
            final Throwable original) {
        try {
            connection.rollback();
        } catch (final SQLException rollbackError) {
            original.addSuppressed(rollbackError);
        }
    }

    private static String sha256(final byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value));
        } catch (final NoSuchAlgorithmException error) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    error);
        }
    }

    @FunctionalInterface
    private interface TransactionWork<T> {
        T apply(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    private interface StatementParameters {
        void apply(PreparedStatement statement) throws SQLException;
    }
}

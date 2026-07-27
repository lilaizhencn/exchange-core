package exchange.core2.core.simulation.http;

/**
 * Normalized HTTP, transport, or wire-contract failure from Emporia.
 */
public final class EmporiaHttpException extends RuntimeException {

    private final Integer statusCode;
    private final boolean retryable;

    EmporiaHttpException(
            final String message,
            final Integer statusCode,
            final boolean retryable,
            final Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.retryable = retryable;
    }

    /**
     * HTTP status, or {@code null} when no valid response was received.
     */
    public Integer statusCode() {
        return statusCode;
    }

    public boolean retryable() {
        return retryable;
    }
}

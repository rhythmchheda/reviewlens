package com.reviewlens.llm;

import org.springframework.web.client.RestClientResponseException;

public class LlmException extends RuntimeException {

    private final boolean retryable;

    public LlmException(String message) {
        this(message, null, true);
    }

    public LlmException(String message, Throwable cause) {
        this(message, cause, true);
    }

    public LlmException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    /** False when retrying cannot help (bad API key, malformed request). */
    public boolean retryable() {
        return retryable;
    }

    /**
     * Classifies a provider failure. Retry: timeouts, connection errors, 5xx, 429 (rate limited).
     * Do not retry: other 4xx, because the same request will fail the same way.
     */
    public static LlmException from(String provider, Exception e) {
        boolean retryable = !(e instanceof RestClientResponseException r)
                || r.getStatusCode().is5xxServerError()
                || r.getStatusCode().value() == 429;
        return new LlmException(provider + " call failed: " + e.getMessage(), e, retryable);
    }
}

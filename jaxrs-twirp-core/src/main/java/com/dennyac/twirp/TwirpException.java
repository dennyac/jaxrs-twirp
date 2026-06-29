package com.dennyac.twirp;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Runtime exception thrown from a Twirp service implementation to produce a
 * Twirp-formatted JSON error response.
 *
 * <p>Use the static factory methods for common cases:
 * <pre>{@code
 * throw TwirpException.invalidArgument("inches", "value must be positive");
 * throw TwirpException.notFound("hat does not exist");
 * }</pre>
 *
 * <p>Or the builder for full control over {@code meta}:
 * <pre>{@code
 * throw TwirpException.builder(ErrorCode.UNAVAILABLE)
 *     .message("taking a nap")
 *     .meta("retryable", "true")
 *     .meta("retry_after", "15s")
 *     .build();
 * }</pre>
 */
public class TwirpException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;
    private final Map<String, String> meta;

    public TwirpException(ErrorCode errorCode, String message) {
        this(errorCode, message, null, Collections.emptyMap());
    }

    public TwirpException(ErrorCode errorCode, String message, Throwable cause) {
        this(errorCode, message, cause, Collections.emptyMap());
    }

    public TwirpException(ErrorCode errorCode, String message, Throwable cause,
                          Map<String, String> meta) {
        super(message, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        this.meta = meta == null || meta.isEmpty()
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(meta));
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /** Always non-null; empty when no meta was supplied. */
    public Map<String, String> getMeta() {
        return meta;
    }

    // --- Convenience static factories matching the most common Twirp codes ----

    public static TwirpException invalidArgument(String argument, String reason) {
        return builder(ErrorCode.INVALID_ARGUMENT)
                .message(argument + ": " + reason)
                .meta("argument", argument)
                .build();
    }

    public static TwirpException notFound(String message) {
        return new TwirpException(ErrorCode.NOT_FOUND, message);
    }

    public static TwirpException internal(String message, Throwable cause) {
        return new TwirpException(ErrorCode.INTERNAL, message, cause);
    }

    public static TwirpException unimplemented(String method) {
        return new TwirpException(ErrorCode.UNIMPLEMENTED,
                "method " + method + " is not implemented");
    }

    // --- Builder ---------------------------------------------------------------

    public static Builder builder(ErrorCode errorCode) {
        return new Builder(errorCode);
    }

    public static final class Builder {
        private final ErrorCode errorCode;
        private String message;
        private Throwable cause;
        private final Map<String, String> meta = new LinkedHashMap<>();

        private Builder(ErrorCode errorCode) {
            this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public Builder cause(Throwable cause) {
            this.cause = cause;
            return this;
        }

        public Builder meta(String key, String value) {
            this.meta.put(Objects.requireNonNull(key, "key"),
                    Objects.requireNonNull(value, "value"));
            return this;
        }

        public TwirpException build() {
            return new TwirpException(errorCode,
                    message == null ? errorCode.wireValue() : message,
                    cause,
                    meta);
        }
    }
}

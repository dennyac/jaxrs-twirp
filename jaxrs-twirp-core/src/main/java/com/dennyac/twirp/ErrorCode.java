// Copyright 2026 the jaxrs-twirp authors
// SPDX-License-Identifier: Apache-2.0

package com.dennyac.twirp;

/**
 * Canonical Twirp error codes (spec v7) and their corresponding HTTP status codes.
 *
 * <p>The string form returned by {@link #wireValue()} is what appears as the
 * {@code "code"} field of a Twirp JSON error response. The HTTP status returned
 * by {@link #httpStatus()} is what the server uses for the HTTP response that
 * carries that JSON.
 *
 * <p>Reference:
 * <a href="https://twitchtv.github.io/twirp/docs/spec_v7.html">Twirp Spec v7</a>.
 */
public enum ErrorCode {
    /** The operation was cancelled. */
    CANCELED("canceled", 408),
    /** An unknown error occurred. */
    UNKNOWN("unknown", 500),
    /** The client specified an invalid argument regardless of the state of the system. */
    INVALID_ARGUMENT("invalid_argument", 400),
    /** The client sent a message which could not be decoded. */
    MALFORMED("malformed", 400),
    /** Operation expired before completion. */
    DEADLINE_EXCEEDED("deadline_exceeded", 408),
    /** Some requested entity was not found. */
    NOT_FOUND("not_found", 404),
    /** The requested URL path was not routable to a Twirp service / method. */
    BAD_ROUTE("bad_route", 404),
    /** An attempt was made to create an entity that already exists. */
    ALREADY_EXISTS("already_exists", 409),
    /** The caller does not have permission to execute the specified operation. */
    PERMISSION_DENIED("permission_denied", 403),
    /** The request does not have valid authentication credentials for the operation. */
    UNAUTHENTICATED("unauthenticated", 401),
    /** Some resource has been exhausted or rate-limited. */
    RESOURCE_EXHAUSTED("resource_exhausted", 429),
    /** The system is not in the required state to execute the operation. */
    FAILED_PRECONDITION("failed_precondition", 412),
    /** The operation was aborted, typically due to a concurrency issue. */
    ABORTED("aborted", 409),
    /** The operation was attempted past the valid range. */
    OUT_OF_RANGE("out_of_range", 400),
    /** The operation is not implemented or not supported / enabled. */
    UNIMPLEMENTED("unimplemented", 501),
    /** Some invariants expected by the underlying system have been broken. */
    INTERNAL("internal", 500),
    /** The service is currently unavailable; this is usually a transient condition. */
    UNAVAILABLE("unavailable", 503),
    /** Unrecoverable data loss or corruption. */
    DATA_LOSS("data_loss", 500);

    private final String wireValue;
    private final int httpStatus;

    ErrorCode(String wireValue, int httpStatus) {
        this.wireValue = wireValue;
        this.httpStatus = httpStatus;
    }

    /** The string value that appears as the {@code "code"} field in the Twirp JSON. */
    public String wireValue() {
        return wireValue;
    }

    /** The HTTP status code the server should return alongside this error. */
    public int httpStatus() {
        return httpStatus;
    }

    /**
     * Look up an {@link ErrorCode} by its wire value, returning {@link #UNKNOWN} if
     * the value is not recognized.
     */
    public static ErrorCode fromWireValue(String wireValue) {
        if (wireValue != null) {
            for (ErrorCode code : values()) {
                if (code.wireValue.equals(wireValue)) {
                    return code;
                }
            }
        }
        return UNKNOWN;
    }
}

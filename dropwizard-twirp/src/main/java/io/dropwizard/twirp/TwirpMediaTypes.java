package io.dropwizard.twirp;

import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;

/**
 * Wire-protocol media types defined by the Twirp v7 spec.
 *
 * <p>The two request/response payload types are exposed here so that generated
 * resource classes can reference them as string constants in
 * {@code @Consumes}/{@code @Produces} annotations.
 */
public final class TwirpMediaTypes {

    /** {@code application/protobuf} — raw proto3 wire bytes. */
    public static final String APPLICATION_PROTOBUF = "application/protobuf";

    /** {@code application/protobuf} as a parsed {@link MediaType}. */
    public static final MediaType APPLICATION_PROTOBUF_TYPE =
            new MediaType("application", "protobuf");

    /**
     * {@code application/json} — canonical protobuf JSON mapping. Reuses Jakarta's
     * built-in constant for source-level identity.
     */
    public static final String APPLICATION_JSON = MediaType.APPLICATION_JSON;

    /** {@code application/json} as a parsed {@link MediaType}. */
    public static final MediaType APPLICATION_JSON_TYPE = MediaType.APPLICATION_JSON_TYPE;

    /**
     * Returns the wire format the server should respond with, derived from the
     * request's {@code Content-Type}.
     *
     * <p>Per the Twirp v7 spec the response Content-Type mirrors the request
     * Content-Type, <em>not</em> the {@code Accept} header — so a client that
     * sent {@code application/json} always gets {@code application/json} back,
     * even if it didn't bother sending {@code Accept: application/json}. This
     * is what lets thin curl-style clients work without any extra headers.
     *
     * <p>If the request Content-Type is missing or unrecognized, this falls
     * back to {@code application/protobuf} — which is what protoc-generated
     * clients send if the user doesn't override.
     */
    public static MediaType responseType(HttpHeaders requestHeaders) {
        if (requestHeaders == null) {
            return APPLICATION_PROTOBUF_TYPE;
        }
        return responseType(requestHeaders.getMediaType());
    }

    /**
     * Variant of {@link #responseType(HttpHeaders)} that takes a parsed
     * {@link MediaType} directly. Package-private static rather than private so
     * tests can exercise the matching logic without standing up a Jersey
     * {@code HttpHeaders}.
     */
    static MediaType responseType(MediaType requestType) {
        if (requestType == null) {
            return APPLICATION_PROTOBUF_TYPE;
        }
        // MediaType#isCompatible handles wildcards in either direction. The
        // common case here is an exact-match string from a Twirp client.
        if (APPLICATION_JSON_TYPE.isCompatible(requestType)) {
            return APPLICATION_JSON_TYPE;
        }
        return APPLICATION_PROTOBUF_TYPE;
    }

    private TwirpMediaTypes() {
        // utility class
    }
}

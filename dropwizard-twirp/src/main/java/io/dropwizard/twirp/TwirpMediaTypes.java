package io.dropwizard.twirp;

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

    private TwirpMediaTypes() {
        // utility class
    }
}

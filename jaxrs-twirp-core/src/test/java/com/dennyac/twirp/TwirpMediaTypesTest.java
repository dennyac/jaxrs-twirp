package com.dennyac.twirp;

import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TwirpMediaTypes#responseType}, the per-request
 * Content-Type mirror used by generated JAX-RS resources to comply with the
 * Twirp v7 spec.
 */
class TwirpMediaTypesTest {

    @Test
    void jsonRequestGetsJsonResponse() {
        assertThat(TwirpMediaTypes.responseType(MediaType.APPLICATION_JSON_TYPE))
                .isEqualTo(TwirpMediaTypes.APPLICATION_JSON_TYPE);
    }

    @Test
    void jsonRequestWithCharsetGetsJsonResponse() {
        // Clients in the wild send "application/json; charset=utf-8" — the
        // matcher needs to treat that as compatible with application/json.
        MediaType withCharset = MediaType.valueOf("application/json; charset=utf-8");
        assertThat(TwirpMediaTypes.responseType(withCharset))
                .isEqualTo(TwirpMediaTypes.APPLICATION_JSON_TYPE);
    }

    @Test
    void protobufRequestGetsProtobufResponse() {
        assertThat(TwirpMediaTypes.responseType(TwirpMediaTypes.APPLICATION_PROTOBUF_TYPE))
                .isEqualTo(TwirpMediaTypes.APPLICATION_PROTOBUF_TYPE);
    }

    @Test
    void missingContentTypeFallsBackToProtobuf() {
        // No Content-Type header → null MediaType → defaults to protobuf
        // because that's what protoc-generated Twirp clients send.
        assertThat(TwirpMediaTypes.responseType((MediaType) null))
                .isEqualTo(TwirpMediaTypes.APPLICATION_PROTOBUF_TYPE);
    }

    @Test
    void unknownContentTypeFallsBackToProtobuf() {
        // text/plain isn't a Twirp wire format. Spec doesn't say what to do —
        // we default to protobuf so the response body is at least decodable
        // by a real Twirp client. The @Consumes annotation will normally
        // reject the request before this code path runs.
        assertThat(TwirpMediaTypes.responseType(MediaType.TEXT_PLAIN_TYPE))
                .isEqualTo(TwirpMediaTypes.APPLICATION_PROTOBUF_TYPE);
    }
}

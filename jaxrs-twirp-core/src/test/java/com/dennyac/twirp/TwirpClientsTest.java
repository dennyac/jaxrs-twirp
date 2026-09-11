// Copyright 2026 the jaxrs-twirp authors
// SPDX-License-Identifier: Apache-2.0

package com.dennyac.twirp;

import jakarta.ws.rs.client.Invocation;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TwirpClientsTest {

    @Test
    void decodeErrorParsesWellFormedTwirpJson() {
        String body = """
                {"code":"not_found","msg":"hat 42 missing","meta":{"id":"42"}}
                """;

        TwirpException ex = TwirpClients.decodeErrorBody(404, body);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(ex.getMessage()).isEqualTo("hat 42 missing");
        assertThat(ex.getMeta()).containsExactly(java.util.Map.entry("id", "42"));
    }

    @Test
    void decodeErrorAcceptsEnvelopeWithoutMeta() {
        String body = "{\"code\":\"invalid_argument\",\"msg\":\"inches must be > 0\"}";

        TwirpException ex = TwirpClients.decodeErrorBody(400, body);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(ex.getMessage()).isEqualTo("inches must be > 0");
        assertThat(ex.getMeta()).isEmpty();
    }

    @Test
    void decodeErrorFallsBackToUnknownForNonJsonBody() {
        TwirpException ex = TwirpClients.decodeErrorBody(
                500, "<html><body>internal server error</body></html>");

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.UNKNOWN);
        assertThat(ex.getMessage())
                .contains("HTTP 500")
                .contains("internal server error");
    }

    @Test
    void decodeErrorFallsBackToUnknownForEmptyBody() {
        TwirpException ex = TwirpClients.decodeErrorBody(502, "");

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.UNKNOWN);
        assertThat(ex.getMessage()).contains("HTTP 502");
    }

    @Test
    void decodeErrorFallsBackForJsonMissingCode() {
        // {"foo": "bar"} parses fine as JSON but isn't a Twirp envelope.
        TwirpException ex = TwirpClients.decodeErrorBody(418, "{\"foo\":\"bar\"}");

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.UNKNOWN);
        assertThat(ex.getMessage())
                .contains("HTTP 418")
                .contains("\"foo\":\"bar\"");
    }

    @Test
    void decodeErrorMapsUnknownWireCodeToUnknownEnum() {
        // future / unknown Twirp code values should not blow up
        TwirpException ex = TwirpClients.decodeErrorBody(
                500, "{\"code\":\"something_new\",\"msg\":\"hello\"}");

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.UNKNOWN);
        assertThat(ex.getMessage()).isEqualTo("hello");
    }

    @Test
    void decodeErrorTruncatesVeryLongBodies() {
        String body = "x".repeat(1000);
        TwirpException ex = TwirpClients.decodeErrorBody(500, body);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.UNKNOWN);
        assertThat(ex.getMessage()).contains("xxx").endsWith("...");
    }

    @Test
    void applyHeadersIgnoresInboundServerHeaders() {
        Map<String, List<String>> applied = new LinkedHashMap<>();
        Invocation.Builder request = recordingBuilder(applied);
        TwirpContext inbound = TwirpContext.of(
                Map.of("Authorization", List.of("******")), null);

        TwirpClients.applyHeaders(request, inbound);

        assertThat(applied).isEmpty();
    }

    @Test
    void applyHeadersCopiesOnlyExplicitOutboundHeaders() {
        Map<String, List<String>> applied = new LinkedHashMap<>();
        Invocation.Builder request = recordingBuilder(applied);
        TwirpContext outbound = TwirpContext.ofOutboundHeaders(Map.of(
                "Authorization", List.of("******"),
                "traceparent", List.of("00-abc-def-01")));

        TwirpClients.applyHeaders(request, outbound);

        assertThat(applied)
                .containsEntry("Authorization", List.of("******"))
                .containsEntry("traceparent", List.of("00-abc-def-01"));
    }

    private static Invocation.Builder recordingBuilder(Map<String, List<String>> applied) {
        Object[] proxyHolder = new Object[1];
        Invocation.Builder proxy = (Invocation.Builder) Proxy.newProxyInstance(
                TwirpClientsTest.class.getClassLoader(),
                new Class<?>[]{Invocation.Builder.class},
                (ignored, method, args) -> {
                    if ("header".equals(method.getName())) {
                        applied.computeIfAbsent((String) args[0], key -> new ArrayList<>())
                                .add((String) args[1]);
                        return proxyHolder[0];
                    }
                    if ("toString".equals(method.getName())) {
                        return "recording Invocation.Builder";
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        proxyHolder[0] = proxy;
        return proxy;
    }
}

// Copyright 2026 the jaxrs-twirp authors
// SPDX-License-Identifier: Apache-2.0

package com.dennyac.twirp;

import com.dennyac.twirp.testproto.TestMessage;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseFilter;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class TwirpClientsTest {

    private static final String PROXY_PAGE = "<html><body><h1>upstream unavailable</h1></body></html>";

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
    void decodeErrorTurnsNullMetaValuesIntoEmptyStrings() {
        TwirpException ex = TwirpClients.decodeErrorBody(
                404, "{\"code\":\"not_found\",\"msg\":\"missing\",\"meta\":{\"id\":null}}");

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(ex.getMeta()).isEqualTo(Map.of("id", ""));
    }

    @ParameterizedTest
    @CsvSource({
            "400, INTERNAL, Bad Request",
            "401, UNAUTHENTICATED, Unauthorized",
            "403, PERMISSION_DENIED, Forbidden",
            "404, BAD_ROUTE, Not Found",
            "429, RESOURCE_EXHAUSTED, Too Many Requests",
            "502, UNAVAILABLE, Bad Gateway",
            "503, UNAVAILABLE, Service Unavailable",
            "504, UNAVAILABLE, Gateway Timeout",
            "500, UNKNOWN, Internal Server Error",
            "409, UNKNOWN, Conflict",
            "418, UNKNOWN, ''",
    })
    void decodeErrorMapsNonTwirpBodiesFromHttpStatus(int status, ErrorCode expected, String statusText) {
        TwirpException ex = TwirpClients.decodeErrorBody(status, PROXY_PAGE);

        assertThat(ex.getErrorCode()).isEqualTo(expected);
        assertThat(ex.getMessage()).isEqualTo(
                "Error from intermediary with HTTP status code " + status + " \"" + statusText + "\"");
        assertThat(ex.getMeta()).isEqualTo(Map.of(
                "http_error_from_intermediary", "true",
                "status_code", Integer.toString(status),
                "body", PROXY_PAGE));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "",
            "upstream connect error or disconnect/reset before headers",
            "null",
            "[]",
            "{\"foo\":\"bar\"}",
            "{\"code\":\"\",\"msg\":\"empty code\"}",
            "{\"code\":\"not_found\",\"msg\":\"extra field\",\"retry\":true}",
            "{\"code\":404,\"msg\":\"numeric code\"}",
            "{\"code\":\"not_found\",\"meta\":{\"attempt\":1}}",
    })
    void decodeErrorTreatsBodiesThatAreNotTwirpErrorsAsIntermediaryErrors(String body) {
        TwirpException ex = TwirpClients.decodeErrorBody(503, body);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.UNAVAILABLE);
        assertThat(ex.getMeta())
                .containsEntry("http_error_from_intermediary", "true")
                .containsEntry("status_code", "503")
                .containsEntry("body", body == null ? "" : body);
    }

    @Test
    void decodeErrorMapsUnrecognizedWireCodeToInternal() {
        String body = "{\"code\":\"something_new\",\"msg\":\"hello\"}";

        TwirpException ex = TwirpClients.decodeErrorBody(500, body);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(ex.getMessage())
                .isEqualTo("invalid type returned from server error response: something_new");
        assertThat(ex.getMeta()).isEqualTo(Map.of("body", body));
    }

    @Test
    void decodeErrorKeepsTheWholeBodyInMetaAndOutOfTheMessage() {
        String body = "x".repeat(100_000);

        TwirpException ex = TwirpClients.decodeErrorBody(502, body);

        assertThat(ex.getMessage())
                .isEqualTo("Error from intermediary with HTTP status code 502 \"Bad Gateway\"");
        assertThat(ex.getMeta().get("body")).isEqualTo(body);
    }

    @Test
    void decodeErrorReportsRedirectWithoutReadingTheBody() {
        TwirpException ex = TwirpClients.decodeErrorBody(
                302, "{\"code\":\"not_found\",\"msg\":\"ignored\"}");

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(ex.getMessage())
                .isEqualTo("unexpected HTTP status code 302 \"Found\" received, Location=\"\"");
        assertThat(ex.getMeta()).isEqualTo(Map.of(
                "http_error_from_intermediary", "true",
                "status_code", "302",
                "location", ""));
    }

    @ParameterizedTest
    @CsvSource({
            "503, UNAVAILABLE",
            "502, UNAVAILABLE",
            "429, RESOURCE_EXHAUSTED",
            "401, UNAUTHENTICATED",
    })
    void invokeMapsProxyErrorPagesFromHttpStatus(int status, ErrorCode expected) {
        TwirpException ex = invokeAborted(
                Response.status(status).type(MediaType.TEXT_HTML_TYPE).entity(PROXY_PAGE).build());

        assertThat(ex.getErrorCode()).isEqualTo(expected);
        assertThat(ex.getMeta()).isEqualTo(Map.of(
                "http_error_from_intermediary", "true",
                "status_code", Integer.toString(status),
                "body", PROXY_PAGE));
    }

    @Test
    void invokeReportsRedirectLocation() {
        String location = "https://login.example.com/sso?next=%2Ftwirp";

        TwirpException ex = invokeAborted(Response.status(302)
                .location(URI.create(location))
                .type(MediaType.TEXT_HTML_TYPE)
                .entity("<html>moved</html>")
                .build());

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(ex.getMessage()).isEqualTo(
                "unexpected HTTP status code 302 \"Found\" received, Location=\"" + location + "\"");
        assertThat(ex.getMeta()).isEqualTo(Map.of(
                "http_error_from_intermediary", "true",
                "status_code", "302",
                "location", location));
    }

    @Test
    void invokeDecodesTwirpErrorWhateverTheContentType() {
        TwirpException ex = invokeAborted(Response.status(404)
                .type(MediaType.TEXT_PLAIN_TYPE)
                .entity("{\"code\":\"not_found\",\"msg\":\"hat 42 missing\",\"meta\":{\"id\":\"42\"}}")
                .build());

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(ex.getMessage()).isEqualTo("hat 42 missing");
        assertThat(ex.getMeta()).isEqualTo(Map.of("id", "42"));
    }

    @Test
    void invokeReportsEmptyErrorBody() {
        TwirpException ex = invokeAborted(Response.status(504).build());

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.UNAVAILABLE);
        assertThat(ex.getMeta()).containsEntry("body", "");
    }

    @Test
    void invokeReportsUnreadableErrorBodyAsInternal() {
        ClientResponseFilter failingBody = (request, response) -> response.setEntityStream(new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("connection reset");
            }
        });

        TwirpException ex = invokeAborted(
                Response.status(503).type(MediaType.TEXT_HTML_TYPE).entity(PROXY_PAGE).build(),
                failingBody);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(ex.getMessage()).isEqualTo("failed to read server error response body: connection reset");
        assertThat(ex.getCause()).isInstanceOf(ProcessingException.class);
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

    private static TwirpException invokeAborted(Response response, ClientResponseFilter... responseFilters) {
        try (Client client = ClientBuilder.newClient()) {
            client.register((ClientRequestFilter) request -> request.abortWith(response), ClientRequestFilter.class);
            for (ClientResponseFilter filter : responseFilters) {
                client.register(filter, ClientResponseFilter.class);
            }
            TwirpClients.registerProviders(client);
            Invocation.Builder request = client.target("http://twirp.invalid")
                    .path("twirp/test.Haberdasher/MakeHat")
                    .request(TwirpMediaTypes.APPLICATION_PROTOBUF)
                    .accept(TwirpMediaTypes.APPLICATION_PROTOBUF);

            Throwable thrown = catchThrowable(() -> TwirpClients.invoke(
                    request,
                    Entity.entity(TestMessage.newBuilder().setHatSize(12).build(),
                            TwirpMediaTypes.APPLICATION_PROTOBUF),
                    TestMessage.class));
            assertThat(thrown).isInstanceOf(TwirpException.class);
            return (TwirpException) thrown;
        }
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

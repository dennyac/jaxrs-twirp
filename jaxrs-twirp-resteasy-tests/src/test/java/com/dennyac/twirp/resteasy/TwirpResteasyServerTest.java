// Copyright 2026 the jaxrs-twirp authors
// SPDX-License-Identifier: Apache-2.0

package com.dennyac.twirp.resteasy;

import com.dennyac.twirp.TwirpMediaTypes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.plugins.server.undertow.UndertowJaxrsServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class TwirpResteasyServerTest {

    @RegisterExtension
    static final ResteasyServer SERVER = new ResteasyServer();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private static final String PROTOBUF = TwirpMediaTypes.APPLICATION_PROTOBUF;
    private static final String JSON = TwirpMediaTypes.APPLICATION_JSON;
    private static final String ECHO = "/twirp/resteasy.test.Echo/";
    private static final EchoMessage MESSAGE = EchoMessage.newBuilder()
            .setHatColor("red").setHatSize(7).addTags("wool").build();
    private static final String MESSAGE_JSON =
            "{\"hat_color\":\"red\",\"hat_size\":7,\"tags\":[\"wool\"]}";

    @Test
    void servesWithResteasy() {
        assertThat(SERVER.instance().unwrap(UndertowJaxrsServer.class)).isNotNull();
    }

    @Test
    void protobufRoundTrip() throws Exception {
        HttpResponse<byte[]> response = post(ECHO + "Echo", PROTOBUF, MESSAGE.toByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue(PROTOBUF);
        assertThat(EchoMessage.parseFrom(response.body())).isEqualTo(MESSAGE);
    }

    @Test
    void jsonRoundTrip() throws Exception {
        HttpResponse<byte[]> response = post(ECHO + "Echo", JSON, MESSAGE_JSON);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue(JSON);
        assertThat(text(response)).isEqualTo(MESSAGE_JSON);
    }

    @Test
    void serviceErrorsAreJsonWithMetadataForBothFormats() throws Exception {
        String expected = "{\"code\":\"invalid_argument\",\"msg\":\"hat_size: must be positive\","
                + "\"meta\":{\"argument\":\"hat_size\"}}";

        assertTwirpError(post(ECHO + "Reject", PROTOBUF, MESSAGE.toByteArray()), 400, expected);
        assertTwirpError(post(ECHO + "Reject", JSON, MESSAGE_JSON), 400, expected);
    }

    @Test
    void undecodableBodiesAreMalformed() throws Exception {
        assertMalformed(post(ECHO + "Echo", PROTOBUF, new byte[] {(byte) 0xFF}));
        assertMalformed(post(ECHO + "Echo", JSON, "{ invalid json"));
    }

    @Test
    void nullResultsAndJavaErrorsAreInternal() throws Exception {
        assertTwirpError(post(ECHO + "ReturnNull", PROTOBUF, MESSAGE.toByteArray()), 500,
                "{\"code\":\"internal\",\"msg\":\"received a null response while calling ReturnNull; "
                        + "null responses are not supported\"}");
        assertTwirpError(post(ECHO + "Crash", JSON, MESSAGE_JSON), 500,
                "{\"code\":\"internal\",\"msg\":\"Crash failed: invariant broken\"}");
    }

    @Test
    void routingFailuresUnderTheTwirpPrefixAreBadRoute() throws Exception {
        assertTwirpError(post(ECHO + "Missing", PROTOBUF, MESSAGE.toByteArray()), 404,
                "{\"code\":\"bad_route\",\"msg\":\"no Twirp handler for the requested URL\"}");

        HttpResponse<byte[]> get = send(HttpRequest.newBuilder(SERVER.uri(ECHO + "Echo")).GET());
        assertTwirpError(get, 404,
                "{\"code\":\"bad_route\",\"msg\":\"Twirp endpoints only accept POST\"}");
        assertThat(get.headers().firstValue("Allow")).isEmpty();

        assertTwirpError(post(ECHO + "Echo", MediaType.TEXT_PLAIN, "not protobuf"), 404,
                "{\"code\":\"bad_route\","
                        + "\"msg\":\"Twirp requires application/protobuf or application/json\"}");
    }

    @Test
    void restRoutingFailuresAreUnchanged() throws Exception {
        assertEmptyResponse(send(HttpRequest.newBuilder(SERVER.uri("/rest/missing")).GET()), 404);

        HttpResponse<byte[]> wrongMethod = send(HttpRequest.newBuilder(SERVER.uri("/rest")).DELETE());
        assertEmptyResponse(wrongMethod, 405);
        assertThat(wrongMethod.headers().firstValue("Allow")).isPresent();

        assertEmptyResponse(post("/rest", MediaType.TEXT_PLAIN, "not json"), 415);
    }

    @Test
    void restJsonComesFromJackson() throws Exception {
        HttpResponse<byte[]> get = send(HttpRequest.newBuilder(SERVER.uri("/rest/json")).GET());
        assertThat(get.statusCode()).isEqualTo(200);
        assertThat(get.headers().firstValue("Content-Type")).hasValue(JSON);
        assertThat(text(get).lines()).containsExactly("{", "  \"display_name\" : \"ordinary REST\"", "}");

        HttpResponse<byte[]> post = post("/rest", JSON, "{\"display_name\":\"posted\"}");
        assertThat(post.statusCode()).isEqualTo(200);
        assertThat(text(post).lines()).containsExactly("{", "  \"display_name\" : \"posted\"", "}");
    }

    @Test
    void twirpContextExposesRequestHeaders() throws Exception {
        HttpResponse<byte[]> response = post(ECHO + "ReadHeader", JSON, "{\"name\":\"x-request-id\"}",
                "X-Request-ID", "request-123");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(text(response)).isEqualTo("{\"values\":[\"request-123\"]}");
    }

    @Test
    void authFilterRunsOnlyForTheSelectedRpc() throws Exception {
        assertTwirpError(post(ECHO + "WhoAmI", JSON, "{}"), 401,
                "{\"code\":\"unauthenticated\",\"msg\":\"missing or invalid bearer token\"}");

        HttpResponse<byte[]> authenticated = post(ECHO + "WhoAmI", JSON, "{}",
                "Authorization", BearerTokenFilter.AUTHORIZATION);
        assertThat(authenticated.statusCode()).isEqualTo(200);
        assertThat(text(authenticated)).isEqualTo("{\"subject\":\"alice\"}");

        HttpResponse<byte[]> unprotected = post(ECHO + "Echo", JSON, MESSAGE_JSON);
        assertThat(unprotected.statusCode()).isEqualTo(200);
    }

    private static HttpResponse<byte[]> post(String path, String contentType, String body, String... headers)
            throws IOException, InterruptedException {
        return post(path, contentType, body.getBytes(StandardCharsets.UTF_8), headers);
    }

    private static HttpResponse<byte[]> post(String path, String contentType, byte[] body, String... headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(SERVER.uri(path))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (headers.length > 0) {
            request.headers(headers);
        }
        return send(request);
    }

    private static HttpResponse<byte[]> send(HttpRequest.Builder request)
            throws IOException, InterruptedException {
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String text(HttpResponse<byte[]> response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private static void assertTwirpError(HttpResponse<byte[]> response, int status, String body) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).hasValue(JSON);
        assertThat(text(response)).isEqualTo(body);
    }

    private static void assertEmptyResponse(HttpResponse<byte[]> response, int status) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).isEmpty();
        assertThat(response.body()).isEmpty();
    }

    private static void assertMalformed(HttpResponse<byte[]> response) throws IOException {
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type")).hasValue(JSON);
        JsonNode body = new ObjectMapper().readTree(response.body());
        assertThat(body.fieldNames()).toIterable().containsExactly("code", "msg");
        assertThat(body.get("code").asText()).isEqualTo("malformed");
        assertThat(body.get("msg").asText()).startsWith("the request payload could not be decoded: ");
    }
}

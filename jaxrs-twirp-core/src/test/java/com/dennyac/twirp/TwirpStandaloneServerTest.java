// Copyright 2026 the jaxrs-twirp authors
// SPDX-License-Identifier: Apache-2.0

package com.dennyac.twirp;

import com.dennyac.twirp.codec.ProtobufJsonMessageBodyWriter;
import com.dennyac.twirp.codec.TwirpErrorMessageBodyWriter;
import com.dennyac.twirp.testproto.TestMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.jakarta.rs.json.JacksonJsonProvider;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.glassfish.jersey.CommonProperties;
import org.glassfish.jersey.internal.MapPropertiesDelegate;
import org.glassfish.jersey.message.MessageBodyWorkers;
import org.glassfish.jersey.server.ApplicationHandler;
import org.glassfish.jersey.server.ContainerRequest;
import org.glassfish.jersey.server.ContainerResponse;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.ServerProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TwirpStandaloneServerTest {

    private static final String ECHO_PATH = "/twirp/test.Echo/Echo";
    private static final TestMessage MESSAGE = TestMessage.newBuilder()
            .setHatColor("red").setHatSize(7).addTags("wool").build();
    private static final String MESSAGE_JSON =
            "{\"hat_color\":\"red\",\"hat_size\":7,\"tags\":[\"wool\"]}";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void registersTwirpWritersWithoutAddingAGeneralJsonProvider(boolean withJsonProvider) {
        try (TestServer server = new TestServer(withJsonProvider)) {
            MessageBodyWorkers workers = server.application.getInjectionManager()
                    .getInstance(MessageBodyWorkers.class);
            Annotation[] annotations = new Annotation[0];

            var restWriter = workers.getMessageBodyWriter(RestMessage.class, RestMessage.class,
                    annotations, MediaType.APPLICATION_JSON_TYPE);
            if (withJsonProvider) {
                assertThat(restWriter).isInstanceOf(JacksonJsonProvider.class);
            } else {
                assertThat(restWriter).as("no automatically discovered JSON provider").isNull();
            }

            assertThat(workers.getMessageBodyWriter(TwirpError.class, TwirpError.class,
                    annotations, MediaType.APPLICATION_JSON_TYPE))
                    .isInstanceOf(TwirpErrorMessageBodyWriter.class);
            assertThat(workers.getMessageBodyWriter(TestMessage.class, TestMessage.class,
                    annotations, MediaType.APPLICATION_JSON_TYPE))
                    .isInstanceOf(ProtobufJsonMessageBodyWriter.class);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void protobufRoundTrip(boolean withJsonProvider) throws Exception {
        try (TestServer server = new TestServer(withJsonProvider)) {
            WireResponse response = server.request("POST", ECHO_PATH,
                    TwirpMediaTypes.APPLICATION_PROTOBUF, MESSAGE.toByteArray());

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.mediaType()).isEqualTo(TwirpMediaTypes.APPLICATION_PROTOBUF_TYPE);
            assertThat(TestMessage.parseFrom(response.bytes())).isEqualTo(MESSAGE);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void jsonRoundTrip(boolean withJsonProvider) throws Exception {
        try (TestServer server = new TestServer(withJsonProvider)) {
            WireResponse response = server.request("POST", ECHO_PATH,
                    MediaType.APPLICATION_JSON, MESSAGE_JSON.getBytes(StandardCharsets.UTF_8));

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.mediaType()).isEqualTo(MediaType.APPLICATION_JSON_TYPE);
            assertThat(response.body()).isEqualTo(MESSAGE_JSON);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void serviceErrorWithMetadataIsJsonEvenForProtobuf(boolean withJsonProvider) throws Exception {
        try (TestServer server = new TestServer(withJsonProvider)) {
            WireResponse response = server.request("POST", "/twirp/test.Echo/WithMeta",
                    TwirpMediaTypes.APPLICATION_PROTOBUF, MESSAGE.toByteArray());

            assertJsonError(response, 400,
                    "{\"code\":\"invalid_argument\",\"msg\":\"hat_size: must be positive\","
                            + "\"meta\":{\"argument\":\"hat_size\"}}");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void serviceErrorOmitsEmptyMetadata(boolean withJsonProvider) throws Exception {
        try (TestServer server = new TestServer(withJsonProvider)) {
            WireResponse response = server.request("POST", "/twirp/test.Echo/WithoutMeta",
                    MediaType.APPLICATION_JSON, MESSAGE_JSON.getBytes(StandardCharsets.UTF_8));

            assertJsonError(response, 404,
                    "{\"code\":\"not_found\",\"msg\":\"hat does not exist\"}");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void malformedProtobufIsJson(boolean withJsonProvider) throws Exception {
        try (TestServer server = new TestServer(withJsonProvider)) {
            WireResponse response = server.request("POST", ECHO_PATH,
                    TwirpMediaTypes.APPLICATION_PROTOBUF, new byte[]{(byte) 0xFF});

            assertMalformed(response);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void malformedJsonIsJson(boolean withJsonProvider) throws Exception {
        try (TestServer server = new TestServer(withJsonProvider)) {
            WireResponse response = server.request("POST", ECHO_PATH,
                    MediaType.APPLICATION_JSON, "{ invalid json".getBytes(StandardCharsets.UTF_8));

            assertMalformed(response);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void routingFailuresAreTwirpErrors(boolean withJsonProvider) throws Exception {
        try (TestServer server = new TestServer(withJsonProvider)) {
            assertJsonError(server.request("POST", "/twirp/test.Echo/Missing",
                            TwirpMediaTypes.APPLICATION_PROTOBUF, MESSAGE.toByteArray()),
                    404, "{\"code\":\"bad_route\",\"msg\":\"no Twirp handler for the requested URL\"}");
            assertJsonError(server.request("GET", ECHO_PATH, null, new byte[0]),
                    404, "{\"code\":\"bad_route\",\"msg\":\"Twirp endpoints only accept POST\"}");
            assertJsonError(server.request("POST", ECHO_PATH, MediaType.TEXT_PLAIN,
                            "not protobuf".getBytes(StandardCharsets.UTF_8)),
                    404, "{\"code\":\"bad_route\","
                            + "\"msg\":\"Twirp requires application/protobuf or application/json\"}");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ordinaryRestResponsesStayUnchanged(boolean withJsonProvider) throws Exception {
        try (TestServer server = new TestServer(withJsonProvider)) {
            WireResponse success = server.request("GET", "/rest", null, new byte[0]);
            assertThat(success.status()).isEqualTo(200);
            assertThat(success.mediaType()).isEqualTo(MediaType.TEXT_PLAIN_TYPE);
            assertThat(success.body()).isEqualTo("ordinary REST");

            WireResponse notFound = server.request("GET", "/rest/missing", null, new byte[0]);
            assertThat(notFound.status()).isEqualTo(404);
            assertThat(notFound.mediaType()).isNull();
            assertThat(notFound.body()).isEmpty();

            WireResponse wrongMethod = server.request("DELETE", "/rest", null, new byte[0]);
            assertThat(wrongMethod.status()).isEqualTo(405);
            assertThat(wrongMethod.mediaType()).isNull();
            assertThat(wrongMethod.body()).isEmpty();

            WireResponse wrongType = server.request("POST", "/rest", MediaType.TEXT_PLAIN,
                    "not json".getBytes(StandardCharsets.UTF_8));
            assertThat(wrongType.status()).isEqualTo(415);
            assertThat(wrongType.mediaType()).isNull();
            assertThat(wrongType.body()).isEmpty();
        }
    }

    @Test
    void ordinaryRestJsonUsesTheConfiguredJsonProvider() throws Exception {
        try (TestServer server = new TestServer(true)) {
            WireResponse response = server.request("GET", "/rest/json", null, new byte[0]);

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.mediaType()).isEqualTo(MediaType.APPLICATION_JSON_TYPE);
            assertThat(response.body()).isEqualTo("{\n  \"display_name\" : \"ordinary REST\"\n}");
        }
    }

    private static void assertJsonError(WireResponse response, int status, String body) {
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.mediaType()).isEqualTo(MediaType.APPLICATION_JSON_TYPE);
        assertThat(response.body()).isEqualTo(body);
    }

    private static void assertMalformed(WireResponse response) throws Exception {
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.mediaType()).isEqualTo(MediaType.APPLICATION_JSON_TYPE);
        JsonNode body = new ObjectMapper().readTree(response.body());
        assertThat(body.fieldNames()).toIterable().containsExactly("code", "msg");
        assertThat(body.get("code").asText()).isEqualTo("malformed");
        assertThat(body.get("msg").asText())
                .startsWith("the request payload could not be decoded: ");
    }

    private record WireResponse(int status, MediaType mediaType, byte[] bytes) {
        String body() {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static final class TestServer implements AutoCloseable {
        private static final URI BASE_URI = URI.create("http://localhost/");
        private final ApplicationHandler application;

        TestServer(boolean withJsonProvider) {
            ResourceConfig config = new ResourceConfig()
                    .property(CommonProperties.FEATURE_AUTO_DISCOVERY_DISABLE, true)
                    .property(CommonProperties.METAINF_SERVICES_LOOKUP_DISABLE, true)
                    .property(ServerProperties.WADL_FEATURE_DISABLE, true);
            if (withJsonProvider) {
                ObjectMapper mapper = new ObjectMapper()
                        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                        .enable(SerializationFeature.INDENT_OUTPUT);
                config.register(new JacksonJsonProvider(mapper));
            }
            config.register(new TwirpServerFeature());
            config.register(EchoResource.class);
            config.register(RestResource.class);
            application = new ApplicationHandler(config);
        }

        WireResponse request(String method, String path, String contentType, byte[] payload)
                throws Exception {
            ContainerRequest request = new ContainerRequest(
                    BASE_URI, BASE_URI.resolve(path), method, null, new MapPropertiesDelegate());
            if (contentType != null) {
                request.getHeaders().putSingle(HttpHeaders.CONTENT_TYPE, contentType);
                request.getHeaders().putSingle(HttpHeaders.ACCEPT, contentType);
            }
            request.setEntityStream(new ByteArrayInputStream(payload));
            ByteArrayOutputStream body = new ByteArrayOutputStream();

            ContainerResponse response = application.apply(request, body).get(10, TimeUnit.SECONDS);
            return new WireResponse(response.getStatus(), response.getMediaType(), body.toByteArray());
        }

        @Override
        public void close() {
            application.onShutdown(null);
        }
    }

    @Path("/twirp/test.Echo")
    @Consumes({TwirpMediaTypes.APPLICATION_PROTOBUF, MediaType.APPLICATION_JSON})
    @Produces({TwirpMediaTypes.APPLICATION_PROTOBUF, MediaType.APPLICATION_JSON})
    public static class EchoResource {
        @POST
        @Path("/Echo")
        public TestMessage echo(TestMessage request) {
            return request;
        }

        @POST
        @Path("/WithMeta")
        public TestMessage withMeta(TestMessage request) throws TwirpException {
            throw TwirpException.invalidArgument("hat_size", "must be positive");
        }

        @POST
        @Path("/WithoutMeta")
        public TestMessage withoutMeta(TestMessage request) throws TwirpException {
            throw TwirpException.notFound("hat does not exist");
        }
    }

    @Path("/rest")
    public static class RestResource {
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String get() {
            return "ordinary REST";
        }

        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.APPLICATION_JSON)
        public String post(String body) {
            return body;
        }

        @GET
        @Path("/json")
        @Produces(MediaType.APPLICATION_JSON)
        public RestMessage json() {
            return new RestMessage("ordinary REST");
        }
    }

    public record RestMessage(String displayName) {}
}

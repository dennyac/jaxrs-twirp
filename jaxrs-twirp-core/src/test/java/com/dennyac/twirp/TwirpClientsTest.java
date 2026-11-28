// Copyright 2026 the jaxrs-twirp authors
// SPDX-License-Identifier: Apache-2.0

package com.dennyac.twirp;

import com.dennyac.twirp.codec.ProtobufJsonMessageBodyReader;
import com.dennyac.twirp.codec.ProtobufJsonMessageBodyWriter;
import com.dennyac.twirp.codec.ProtobufMessageBodyReader;
import com.dennyac.twirp.codec.ProtobufMessageBodyWriter;
import com.dennyac.twirp.testproto.Parcel;
import com.dennyac.twirp.testproto.TestMessage;
import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.TypeRegistry;
import com.google.protobuf.util.JsonFormat;
import com.sun.net.httpserver.HttpServer;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseFilter;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.ResponseProcessingException;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Configurable;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

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

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"code\":\"not_found\"}",
            "{\"code\":\"not_found\",\"msg\":null}",
            "{\"code\":\"not_found\",\"msg\":\"\"}",
    })
    void decodeErrorPreservesEmptyOrOmittedMsg(String body) {
        TwirpException ex = TwirpClients.decodeErrorBody(404, body);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(ex.getMessage()).isEmpty();
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
    void invokeMapsRequestEncodingFailuresToInternal() throws IOException {
        Parcel unregisteredAny = Parcel.newBuilder()
                .setLabel("mystery")
                .setContents(Any.pack(TestMessage.newBuilder().setHatSize(12).build()))
                .build();

        HttpServer server = startEchoServer();
        try (Client client = ClientBuilder.newClient()) {
            TwirpClients.registerProviders(client);
            Invocation.Builder request = client.target("http://127.0.0.1:" + server.getAddress().getPort())
                    .path("twirp/test.Parcels/Send")
                    .request(TwirpMediaTypes.APPLICATION_JSON);

            Throwable thrown = catchThrowable(() -> TwirpClients.invoke(
                    request,
                    Entity.entity(unregisteredAny, TwirpMediaTypes.APPLICATION_JSON),
                    Parcel.class));

            assertThat(thrown).isInstanceOfSatisfying(TwirpException.class, ex -> {
                assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INTERNAL);
                assertThat(ex.getCause()).isInstanceOf(ProcessingException.class);
                assertThat(ex.getCause()).hasCauseInstanceOf(InvalidProtocolBufferException.class);
            });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void invokeMapsMissingMessageBodyWriterToInternal() throws IOException {
        HttpServer server = startEchoServer();
        try (Client client = ClientBuilder.newClient()) {
            // Deliberately omit TwirpClients.registerProviders so Jersey fails provider lookup.
            Invocation.Builder request = client.target("http://127.0.0.1:" + server.getAddress().getPort())
                    .path("twirp/test.Haberdasher/MakeHat")
                    .request(TwirpMediaTypes.APPLICATION_PROTOBUF);

            Throwable thrown = catchThrowable(() -> TwirpClients.invoke(
                    request,
                    Entity.entity(TestMessage.newBuilder().setHatSize(12).build(),
                            TwirpMediaTypes.APPLICATION_PROTOBUF),
                    TestMessage.class));

            assertThat(thrown).isInstanceOfSatisfying(TwirpException.class, ex -> {
                assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INTERNAL);
                assertThat(ex.getCause()).isInstanceOf(ProcessingException.class);
            });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void invokeMapsJsonSerializationFailureToInternal() {
        ProcessingException failure = new ProcessingException(
                new JsonGenerationException("bad json", (JsonGenerator) null));

        TwirpException ex = invokeFailingPost(failure);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(ex.getCause()).isSameAs(failure);
    }

    @Test
    void invokeMapsSocketTimeoutToDeadlineExceeded() {
        ProcessingException failure = new ProcessingException(
                new IOException("wrapped", new SocketTimeoutException("Read timed out")));

        TwirpException ex = invokeFailingPost(failure);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.DEADLINE_EXCEEDED);
        assertThat(ex.getMessage()).isEqualTo("twirp request timed out: Read timed out");
        assertThat(ex.getCause()).isSameAs(failure);
    }

    @Test
    void invokeMapsHttpTimeoutToDeadlineExceeded() {
        ProcessingException failure = new ProcessingException(
                new HttpTimeoutException("request timed out"));

        TwirpException ex = invokeFailingPost(failure);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.DEADLINE_EXCEEDED);
        assertThat(ex.getMessage()).isEqualTo("twirp request timed out: request timed out");
        assertThat(ex.getCause()).isSameAs(failure);
    }

    @Test
    void invokeMapsConcurrentTimeoutToDeadlineExceeded() {
        ProcessingException failure = new ProcessingException(
                new TimeoutException("pool wait timed out"));

        TwirpException ex = invokeFailingPost(failure);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.DEADLINE_EXCEEDED);
        assertThat(ex.getCause()).isSameAs(failure);
    }

    @Test
    void invokeMapsOtherTransportFailuresToUnavailable() {
        ProcessingException failure = new ProcessingException(
                new ConnectException("Connection refused"));

        TwirpException ex = invokeFailingPost(failure);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.UNAVAILABLE);
        assertThat(ex.getMessage()).isEqualTo("twirp transport failure: Connection refused");
        assertThat(ex.getCause()).isSameAs(failure);
    }

    @Test
    void invokeClosesResponseOnResponseProcessingExceptionAndDoesNotTreatAsRequestEncoding() {
        AtomicBoolean closed = new AtomicBoolean();
        Response response = trackingResponse(closed);
        ResponseProcessingException failure = new ResponseProcessingException(
                response, new JsonGenerationException("bad json", (JsonGenerator) null));

        TwirpException ex = invokeFailingPost(failure);

        assertThat(closed).isTrue();
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.UNAVAILABLE);
        assertThat(ex.getMessage()).isEqualTo("twirp transport failure: bad json");
        assertThat(ex.getCause()).isSameAs(failure);
    }

    @Test
    void invokeClosesResponseAndMapsTimeoutOnResponseProcessingException() {
        AtomicBoolean closed = new AtomicBoolean();
        Response response = trackingResponse(closed);
        ResponseProcessingException failure = new ResponseProcessingException(
                response, new SocketTimeoutException("Read timed out"));

        TwirpException ex = invokeFailingPost(failure);

        assertThat(closed).isTrue();
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.DEADLINE_EXCEEDED);
        assertThat(ex.getMessage()).isEqualTo("twirp request timed out: Read timed out");
        assertThat(ex.getCause()).isSameAs(failure);
    }

    @Test
    void invokeMaps2xxReadEntityTimeoutToDeadlineExceeded() {
        ClientResponseFilter timeoutBody = (request, response) -> response.setEntityStream(new InputStream() {
            @Override
            public int read() throws IOException {
                throw new SocketTimeoutException("Read timed out");
            }
        });

        TwirpException ex = invokeAborted(
                Response.ok().type(TwirpMediaTypes.APPLICATION_PROTOBUF_TYPE).entity(new byte[]{1}).build(),
                timeoutBody);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.DEADLINE_EXCEEDED);
        assertThat(ex.getMessage()).isEqualTo("twirp request timed out: Read timed out");
        assertThat(ex.getCause()).isInstanceOf(ProcessingException.class);
    }

    @Test
    void invokeMapsErrorBodyReadTimeoutToDeadlineExceeded() {
        ClientResponseFilter timeoutBody = (request, response) -> response.setEntityStream(new InputStream() {
            @Override
            public int read() throws IOException {
                throw new SocketTimeoutException("Read timed out");
            }
        });

        TwirpException ex = invokeAborted(
                Response.status(503).type(MediaType.TEXT_HTML_TYPE).entity(PROXY_PAGE).build(),
                timeoutBody);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.DEADLINE_EXCEEDED);
        assertThat(ex.getMessage()).isEqualTo("twirp request timed out: Read timed out");
        assertThat(ex.getCause()).isInstanceOf(ProcessingException.class);
    }

    @Test
    void registerProvidersSkipsAlreadyRegisteredClasses() {
        Set<Class<?>> registered = new HashSet<>();
        List<Object> registerCalls = new ArrayList<>();
        Configurable<?> target = recordingConfigurable(registered, registerCalls);

        TwirpClients.registerProviders(target);
        assertThat(registerCalls)
                .extracting(Object::getClass)
                .containsExactlyInAnyOrder(
                        ProtobufMessageBodyReader.class,
                        ProtobufMessageBodyWriter.class,
                        ProtobufJsonMessageBodyReader.class,
                        ProtobufJsonMessageBodyWriter.class);

        registerCalls.clear();
        TwirpClients.registerProviders(target);
        assertThat(registerCalls).isEmpty();
    }

    @Test
    void registerProvidersOnlyRegistersMissingDefaultsWhenJsonCodecsPresent() {
        Set<Class<?>> registered = new HashSet<>(Set.of(
                ProtobufJsonMessageBodyReader.class,
                ProtobufJsonMessageBodyWriter.class));
        List<Object> registerCalls = new ArrayList<>();
        Configurable<?> target = recordingConfigurable(registered, registerCalls);

        TwirpClients.registerProviders(target);

        assertThat(registerCalls)
                .extracting(Object::getClass)
                .containsExactlyInAnyOrder(
                        ProtobufMessageBodyReader.class,
                        ProtobufMessageBodyWriter.class);
    }

    @Test
    void registerProvidersPreservesCustomJsonCodecsOnParentClient() throws IOException {
        TypeRegistry registry = TypeRegistry.newBuilder()
                .add(TestMessage.getDescriptor())
                .build();
        JsonFormat.Printer printer = TwirpJson.defaultPrinter().usingTypeRegistry(registry);
        JsonFormat.Parser parser = TwirpJson.defaultParser().usingTypeRegistry(registry);

        Parcel parcel = Parcel.newBuilder()
                .setLabel("box")
                .setContents(Any.pack(TestMessage.newBuilder()
                        .setHatColor("blue")
                        .setHatSize(9)
                        .build()))
                .build();

        HttpServer server = startEchoServer();
        try (Client client = ClientBuilder.newClient()) {
            TwirpClients.registerProviders(client, printer, parser);
            WebTarget target = client.target("http://127.0.0.1:" + server.getAddress().getPort());
            int beforeCount = target.getConfiguration().getInstances().size();

            TwirpClients.registerProviders(target);

            assertThat(target.getConfiguration().getInstances()).hasSize(beforeCount);

            Parcel echoed = TwirpClients.invoke(
                    target.path("twirp/test.Parcels/Echo").request(TwirpMediaTypes.APPLICATION_JSON),
                    Entity.entity(parcel, TwirpMediaTypes.APPLICATION_JSON),
                    Parcel.class);
            assertThat(echoed).isEqualTo(parcel);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void registerProvidersAddsMissingDefaultsWithoutOverwritingCustomJsonCodecs() {
        TypeRegistry registry = TypeRegistry.newBuilder()
                .add(TestMessage.getDescriptor())
                .build();
        ProtobufJsonMessageBodyReader customReader =
                new ProtobufJsonMessageBodyReader(TwirpJson.defaultParser().usingTypeRegistry(registry));
        ProtobufJsonMessageBodyWriter customWriter =
                new ProtobufJsonMessageBodyWriter(TwirpJson.defaultPrinter().usingTypeRegistry(registry));

        try (Client client = ClientBuilder.newClient()) {
            client.register(customReader);
            client.register(customWriter);
            WebTarget target = client.target("http://twirp.invalid");

            TwirpClients.registerProviders(target);

            assertThat(target.getConfiguration().isRegistered(ProtobufMessageBodyReader.class)).isTrue();
            assertThat(target.getConfiguration().isRegistered(ProtobufMessageBodyWriter.class)).isTrue();
            assertThat(target.getConfiguration().getInstances())
                    .filteredOn(ProtobufJsonMessageBodyReader.class::isInstance)
                    .containsExactly(customReader);
            assertThat(target.getConfiguration().getInstances())
                    .filteredOn(ProtobufJsonMessageBodyWriter.class::isInstance)
                    .containsExactly(customWriter);
        }
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

    private static TwirpException invokeFailingPost(ProcessingException failure) {
        Invocation.Builder request = (Invocation.Builder) Proxy.newProxyInstance(
                TwirpClientsTest.class.getClassLoader(),
                new Class<?>[]{Invocation.Builder.class},
                (ignored, method, args) -> {
                    if ("post".equals(method.getName())) {
                        throw failure;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        Throwable thrown = catchThrowable(() -> TwirpClients.invoke(
                request,
                Entity.entity(TestMessage.newBuilder().setHatSize(12).build(),
                        TwirpMediaTypes.APPLICATION_PROTOBUF),
                TestMessage.class));
        assertThat(thrown).isInstanceOf(TwirpException.class);
        return (TwirpException) thrown;
    }

    private static HttpServer startEchoServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType != null) {
                exchange.getResponseHeaders().set("Content-Type", contentType);
            }
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return server;
    }

    private static Response trackingResponse(AtomicBoolean closed) {
        return new Response() {
            @Override
            public void close() {
                closed.set(true);
            }

            @Override public int getStatus() { return 200; }
            @Override public StatusType getStatusInfo() { return Status.OK; }
            @Override public Object getEntity() { return null; }
            @Override public <T> T readEntity(Class<T> entityType) { throw new UnsupportedOperationException(); }
            @Override public <T> T readEntity(jakarta.ws.rs.core.GenericType<T> entityType) { throw new UnsupportedOperationException(); }
            @Override public <T> T readEntity(Class<T> entityType, java.lang.annotation.Annotation[] annotations) { throw new UnsupportedOperationException(); }
            @Override public <T> T readEntity(jakarta.ws.rs.core.GenericType<T> entityType, java.lang.annotation.Annotation[] annotations) { throw new UnsupportedOperationException(); }
            @Override public boolean hasEntity() { return false; }
            @Override public boolean bufferEntity() { return false; }
            @Override public MediaType getMediaType() { return null; }
            @Override public java.util.Locale getLanguage() { return null; }
            @Override public int getLength() { return -1; }
            @Override public Set<String> getAllowedMethods() { return Set.of(); }
            @Override public Map<String, jakarta.ws.rs.core.NewCookie> getCookies() { return Map.of(); }
            @Override public jakarta.ws.rs.core.EntityTag getEntityTag() { return null; }
            @Override public java.util.Date getDate() { return null; }
            @Override public java.util.Date getLastModified() { return null; }
            @Override public URI getLocation() { return null; }
            @Override public Set<jakarta.ws.rs.core.Link> getLinks() { return Set.of(); }
            @Override public boolean hasLink(String relation) { return false; }
            @Override public jakarta.ws.rs.core.Link getLink(String relation) { return null; }
            @Override public jakarta.ws.rs.core.Link.Builder getLinkBuilder(String relation) { return null; }
            @Override public jakarta.ws.rs.core.MultivaluedMap<String, Object> getMetadata() { return null; }
            @Override public jakarta.ws.rs.core.MultivaluedMap<String, String> getStringHeaders() { return null; }
            @Override public String getHeaderString(String name) { return null; }
        };
    }

    private static Configurable<?> recordingConfigurable(Set<Class<?>> registered, List<Object> registerCalls) {
        Configuration config = (Configuration) Proxy.newProxyInstance(
                TwirpClientsTest.class.getClassLoader(),
                new Class<?>[]{Configuration.class},
                (ignored, method, args) -> {
                    if ("isRegistered".equals(method.getName()) && args.length == 1 && args[0] instanceof Class<?> cls) {
                        return registered.contains(cls);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        Object[] proxyHolder = new Object[1];
        Configurable<?> configurable = (Configurable<?>) Proxy.newProxyInstance(
                TwirpClientsTest.class.getClassLoader(),
                new Class<?>[]{Configurable.class},
                (ignored, method, args) -> {
                    if ("getConfiguration".equals(method.getName())) {
                        return config;
                    }
                    if ("register".equals(method.getName()) && args.length == 1) {
                        registerCalls.add(args[0]);
                        registered.add(args[0] instanceof Class<?> cls ? cls : args[0].getClass());
                        return proxyHolder[0];
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        proxyHolder[0] = configurable;
        return configurable;
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

// Copyright 2026 the jaxrs-twirp authors
// SPDX-License-Identifier: Apache-2.0

package com.dennyac.twirp.dropwizard;

import com.dennyac.twirp.ErrorCode;
import com.dennyac.twirp.TwirpClients;
import com.dennyac.twirp.TwirpException;
import com.dennyac.twirp.TwirpMediaTypes;
import com.google.protobuf.Any;
import com.google.protobuf.SourceContext;
import com.google.protobuf.TypeRegistry;
import com.google.protobuf.util.JsonFormat;
import com.sun.net.httpserver.HttpServer;
import io.dropwizard.client.JerseyClientConfiguration;
import io.dropwizard.core.setup.Environment;
import io.dropwizard.logging.common.BootstrapLogging;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link TwirpClientBuilder}'s wiring: wire-format selection,
 * root target resolution, JSON/TypeRegistry configuration, managed client
 * naming, and the guard rails around an unconfigured build.
 */
class TwirpClientBuilderTest {

    private Client client;

    @BeforeAll
    static void bootstrapLogging() {
        BootstrapLogging.bootstrap();
    }

    @BeforeEach
    void newClient() {
        client = ClientBuilder.newClient();
    }

    @AfterEach
    void closeClient() {
        client.close();
    }

    @Test
    void defaultsToProtobufWireFormat() {
        // The factory just echoes the content type the builder selected.
        String contentType = TwirpClientBuilder.forService((target, ct) -> ct)
                .using(client)
                .baseUri("http://localhost:8080")
                .build();

        assertThat(contentType).isEqualTo(TwirpMediaTypes.APPLICATION_PROTOBUF);
    }

    @Test
    void jsonSelectsJsonWireFormat() {
        String contentType = TwirpClientBuilder.forService((target, ct) -> ct)
                .using(client)
                .baseUri("http://localhost:8080")
                .json()
                .build();

        assertThat(contentType).isEqualTo(TwirpMediaTypes.APPLICATION_JSON);
    }

    @Test
    void explicitContentTypeWins() {
        String contentType = TwirpClientBuilder.forService((target, ct) -> ct)
                .using(client)
                .baseUri("http://localhost:8080")
                .json()
                .contentType(TwirpMediaTypes.APPLICATION_PROTOBUF)
                .build();

        assertThat(contentType).isEqualTo(TwirpMediaTypes.APPLICATION_PROTOBUF);
    }

    @Test
    void passesRootTargetDerivedFromBaseUriToFactory() {
        AtomicReference<WebTarget> captured = new AtomicReference<>();
        TwirpClientBuilder.forService((target, ct) -> {
                    captured.set(target);
                    return ct;
                })
                .using(client)
                .baseUri("http://example.test:9000")
                .build();

        // The builder hands the generated constructor the *root* target; the
        // generated client itself appends the Twirp service path.
        assertThat(captured.get().getUri().toString()).startsWith("http://example.test:9000");
    }

    @Test
    void buildWithoutBaseUriIsRejected() {
        TwirpClientBuilder<String> builder = TwirpClientBuilder.<String>forService((target, ct) -> ct)
                .using(client);

        assertThatThrownBy(builder::build)
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("baseUri");
    }

    @Test
    void buildWithoutAClientSourceIsRejected() {
        TwirpClientBuilder<String> builder = TwirpClientBuilder.<String>forService((target, ct) -> ct)
                .baseUri("http://localhost:8080");

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No HTTP client configured");
    }

    @Test
    void jsonConfigurationRejectsNullArguments() {
        TwirpClientBuilder<WebTarget> builder = TwirpClientBuilder.forService((target, ct) -> target);

        assertThatNullPointerException().isThrownBy(() -> builder.jsonPrinter(null));
        assertThatNullPointerException().isThrownBy(() -> builder.jsonParser(null));
        assertThatNullPointerException().isThrownBy(() -> builder.typeRegistry(null));
    }

    @Test
    void customJsonPrinterTakesPrecedenceOverGeneratedClientRegistration() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        try (TestHttpServer server = new TestHttpServer(body -> {
            receivedBody.set(body);
            return body;
        })) {
            // Simulate a generated client constructor calling TwirpClients.registerProviders(target)
            // after the builder has already registered custom JSON providers on the target.
            WebTarget target = TwirpClientBuilder.forService((root, ct) -> {
                        TwirpClients.registerProviders(root);
                        return root.path("/twirp/test.Echo/Echo");
                    })
                    .using(client)
                    .baseUri(server.baseUri())
                    .json()
                    .jsonPrinter(JsonFormat.printer().omittingInsignificantWhitespace())
                    .build();

            SourceContext response = TwirpClients.invoke(
                    target.request(TwirpMediaTypes.APPLICATION_JSON)
                            .accept(TwirpMediaTypes.APPLICATION_JSON),
                    Entity.entity(SourceContext.newBuilder().setFileName("hat.proto").build(),
                            TwirpMediaTypes.APPLICATION_JSON),
                    SourceContext.class);

            assertThat(receivedBody.get()).isEqualTo("{\"fileName\":\"hat.proto\"}");
            assertThat(response.getFileName()).isEqualTo("hat.proto");
        }
    }

    @Test
    void customJsonParserTakesPrecedenceOverGeneratedClientRegistration() throws Exception {
        try (TestHttpServer server = new TestHttpServer(
                body -> "{\"file_name\":\"hat.proto\",\"unexpected\":true}")) {
            WebTarget target = TwirpClientBuilder.forService((root, ct) -> {
                        TwirpClients.registerProviders(root);
                        return root.path("/twirp/test.Echo/Echo");
                    })
                    .using(client)
                    .baseUri(server.baseUri())
                    .json()
                    .jsonParser(JsonFormat.parser())
                    .build();

            assertThatThrownBy(() -> TwirpClients.invoke(
                    target.request(TwirpMediaTypes.APPLICATION_JSON)
                            .accept(TwirpMediaTypes.APPLICATION_JSON),
                    Entity.entity(SourceContext.newBuilder().setFileName("hat.proto").build(),
                            TwirpMediaTypes.APPLICATION_JSON),
                    SourceContext.class))
                    .isInstanceOfSatisfying(TwirpException.class, ex ->
                            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MALFORMED));
        }
    }

    @Test
    void typeRegistryEnablesAnyOverJsonRoundTrip() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        TypeRegistry registry = TypeRegistry.newBuilder()
                .add(SourceContext.getDescriptor())
                .build();
        try (TestHttpServer server = new TestHttpServer(body -> {
            receivedBody.set(body);
            return body;
        })) {
            WebTarget target = TwirpClientBuilder.forService((root, ct) -> {
                        TwirpClients.registerProviders(root);
                        return root.path("/twirp/test.Echo/Echo");
                    })
                    .using(client)
                    .baseUri(server.baseUri())
                    .json()
                    .typeRegistry(registry)
                    .build();

            Any packed = Any.pack(SourceContext.newBuilder().setFileName("hat.proto").build());
            Any response = TwirpClients.invoke(
                    target.request(TwirpMediaTypes.APPLICATION_JSON)
                            .accept(TwirpMediaTypes.APPLICATION_JSON),
                    Entity.entity(packed, TwirpMediaTypes.APPLICATION_JSON),
                    Any.class);

            assertThat(receivedBody.get())
                    .contains("\"@type\":\"type.googleapis.com/google.protobuf.SourceContext\"")
                    .contains("\"file_name\":\"hat.proto\"");
            assertThat(response.unpack(SourceContext.class).getFileName()).isEqualTo("hat.proto");
        }
    }

    @Test
    void duplicateTypeRegistryOnPrinterAndBuilderFailsAtBuildTime() {
        TypeRegistry registry = TypeRegistry.newBuilder()
                .add(SourceContext.getDescriptor())
                .build();
        TwirpClientBuilder<WebTarget> builder = TwirpClientBuilder.forService((root, ct) -> root)
                .using(client)
                .baseUri("http://localhost:8080")
                .jsonPrinter(JsonFormat.printer().usingTypeRegistry(registry))
                .typeRegistry(registry);

        assertThatIllegalArgumentException().isThrownBy(builder::build);
    }

    @Test
    void customJsonOnClientWithExistingTwirpProvidersIsRejected() {
        TwirpClients.registerProviders(client);
        TypeRegistry registry = TypeRegistry.newBuilder()
                .add(SourceContext.getDescriptor())
                .build();

        assertThatIllegalStateException().isThrownBy(() -> TwirpClientBuilder.forService((root, ct) -> root)
                .using(client)
                .baseUri("http://localhost:8080")
                .json()
                .jsonPrinter(JsonFormat.printer())
                .build());
        assertThatIllegalStateException().isThrownBy(() -> TwirpClientBuilder.forService((root, ct) -> root)
                .using(client)
                .baseUri("http://localhost:8080")
                .json()
                .jsonParser(JsonFormat.parser())
                .build());
        assertThatIllegalStateException().isThrownBy(() -> TwirpClientBuilder.forService((root, ct) -> root)
                .using(client)
                .baseUri("http://localhost:8080")
                .json()
                .typeRegistry(registry)
                .build());

        // Without custom JSON settings, reusing a Client with Twirp providers remains allowed.
        WebTarget target = TwirpClientBuilder.forService((root, ct) -> root)
                .using(client)
                .baseUri("http://localhost:8080")
                .json()
                .build();
        assertThat(target.getUri().toString()).startsWith("http://localhost:8080");
    }

    @Test
    void invalidContentTypeFailsBeforeCreatingManagedClient() throws Exception {
        Environment environment = new Environment("twirp-client-builder-content-type-test");
        Set<String> initialMetrics = Set.copyOf(environment.metrics().getNames());
        int initialManagedCount = environment.lifecycle().getManagedObjects().size();
        try {
            for (String invalid : List.of("text/plain", "*/*", "application/*", "not-a-media-type")) {
                TwirpClientBuilder<WebTarget> builder = TwirpClientBuilder.forService((root, ct) -> root)
                        .using(environment, new JerseyClientConfiguration())
                        .baseUri("http://localhost:8080")
                        .contentType(invalid);
                assertThatIllegalArgumentException().isThrownBy(builder::build);
            }
            assertThat(environment.metrics().getNames()).containsExactlyInAnyOrderElementsOf(initialMetrics);
            assertThat(environment.lifecycle().getManagedObjects()).hasSize(initialManagedCount);

            String accepted = TwirpClientBuilder.forService((root, ct) -> ct)
                    .using(client)
                    .baseUri("http://localhost:8080")
                    .contentType("application/json; charset=utf-8")
                    .build();
            assertThat(accepted).isEqualTo("application/json; charset=utf-8");
        } finally {
            stopManagedObjects(environment);
        }
    }

    @Test
    void defaultManagedClientNamesDoNotCollideAndExcludeBaseUri() throws Exception {
        Environment environment = new Environment("twirp-client-builder-collision-test");
        try {
            String baseUri = "http://user:secret@localhost:8080/api%20v1";
            WebTarget first = TwirpClientBuilder.forService((root, ct) -> root)
                    .using(environment, new JerseyClientConfiguration())
                    .baseUri(baseUri)
                    .build();
            WebTarget second = TwirpClientBuilder.forService((root, ct) -> root)
                    .using(environment, new JerseyClientConfiguration())
                    .baseUri(baseUri)
                    .build();

            assertThat(first.getUri().toString()).startsWith(baseUri);
            assertThat(second.getUri().toString()).startsWith(baseUri);
            assertThat(environment.metrics().getNames())
                    .noneMatch(name -> name.contains("secret") || name.contains("localhost"));

            Pattern defaultName = Pattern.compile("twirp-client-\\d+");
            long distinctDefaultNames = environment.metrics().getNames().stream()
                    .map(defaultName::matcher)
                    .filter(Matcher::find)
                    .map(Matcher::group)
                    .distinct()
                    .count();
            assertThat(distinctDefaultNames).isEqualTo(2);
        } finally {
            stopManagedObjects(environment);
        }
    }

    @Test
    void explicitManagedClientNameIsPreserved() throws Exception {
        Environment environment = new Environment("twirp-client-builder-explicit-name-test");
        try {
            TwirpClientBuilder.forService((root, ct) -> root)
                    .using(environment, new JerseyClientConfiguration())
                    .baseUri("http://localhost:8080")
                    .clientName("haberdasher")
                    .build();

            assertThat(environment.metrics().getNames())
                    .anyMatch(name -> name.contains(".haberdasher."));

            TwirpClientBuilder<WebTarget> duplicate = TwirpClientBuilder.forService((root, ct) -> root)
                    .using(environment, new JerseyClientConfiguration())
                    .baseUri("http://localhost:8080")
                    .clientName("haberdasher");
            assertThatIllegalArgumentException().isThrownBy(duplicate::build);
        } finally {
            stopManagedObjects(environment);
        }
    }

    private static void stopManagedObjects(Environment environment) throws Exception {
        for (LifeCycle managed : environment.lifecycle().getManagedObjects()) {
            // Jetty's AbstractLifeCycle.stop() is a no-op if start() was never called.
            if (!managed.isRunning()) {
                managed.start();
            }
            managed.stop();
        }
    }

    private static final class TestHttpServer implements AutoCloseable {
        @FunctionalInterface
        interface Handler {
            String handle(String requestBody);
        }

        private final HttpServer server;

        TestHttpServer(Handler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                byte[] responseBytes = handler.handle(requestBody).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", TwirpMediaTypes.APPLICATION_JSON);
                exchange.sendResponseHeaders(200, responseBytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(responseBytes);
                }
            });
            server.start();
        }

        String baseUri() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}

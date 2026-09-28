// Copyright 2026 the jaxrs-twirp authors
// SPDX-License-Identifier: Apache-2.0

package com.dennyac.twirp.dropwizard;

import com.google.protobuf.SourceContext;
import com.google.protobuf.util.JsonFormat;
import io.dropwizard.core.Configuration;
import io.dropwizard.core.setup.Environment;
import io.dropwizard.logging.common.BootstrapLogging;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.glassfish.jersey.internal.MapPropertiesDelegate;
import org.glassfish.jersey.server.ApplicationHandler;
import org.glassfish.jersey.server.ContainerRequest;
import org.glassfish.jersey.server.ContainerResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Runs {@link TwirpBundle} against a Dropwizard {@link Environment} and sends
 * requests through the resulting Jersey application in memory.
 */
class TwirpBundleTest {

    private static final String ECHO_REQUEST = "{\"file_name\":\"hat.proto\"}";
    private static final Reply BAD_ROUTE = new Reply(404,
            "{\"code\":\"bad_route\",\"msg\":\"no Twirp handler for the requested URL\"}");
    private static final Reply PLAIN_NOT_FOUND = new Reply(404, "");

    @BeforeAll
    static void bootstrapLogging() {
        // Application.run normally does this; without it Logback logs everything at DEBUG.
        BootstrapLogging.bootstrap();
    }

    @Test
    void defaultBundleServesTwirpBeneathTheDefaultPrefix() throws Exception {
        try (TestServer server = new TestServer(new TwirpBundle<>())) {
            assertThat(server.post("/twirp/test.Echo/Echo", ECHO_REQUEST))
                    .isEqualTo(new Reply(200, ECHO_REQUEST));
            assertThat(server.post("/twirp/test.Echo/Missing", ECHO_REQUEST)).isEqualTo(BAD_ROUTE);
            assertThat(server.post("/rpc/test.Echo/Missing", ECHO_REQUEST)).isEqualTo(PLAIN_NOT_FOUND);
        }
    }

    @Test
    void builderPathPrefixesReplaceTheDefaultPrefix() throws Exception {
        TwirpBundle<Configuration> bundle = TwirpBundle.builder()
                .pathPrefixes("rpc/", "/internal")
                .build();

        try (TestServer server = new TestServer(bundle)) {
            assertThat(server.post("/rpc/test.Echo/Missing", ECHO_REQUEST)).isEqualTo(BAD_ROUTE);
            assertThat(server.post("/internal/test.Echo/Missing", ECHO_REQUEST)).isEqualTo(BAD_ROUTE);
            assertThat(server.post("/twirp/test.Echo/Missing", ECHO_REQUEST)).isEqualTo(PLAIN_NOT_FOUND);
        }
    }

    @Test
    void builderJsonPrinterIsUsedForResponses() throws Exception {
        TwirpBundle<Configuration> bundle = TwirpBundle.builder()
                .jsonPrinter(JsonFormat.printer().omittingInsignificantWhitespace())
                .build();

        try (TestServer server = new TestServer(bundle)) {
            assertThat(server.post("/twirp/test.Echo/Echo", ECHO_REQUEST))
                    .isEqualTo(new Reply(200, "{\"fileName\":\"hat.proto\"}"));
        }
    }

    @Test
    void invalidPathPrefixesFailWhenTheBundleIsBuilt() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> TwirpBundle.builder().pathPrefixes().build());
        assertThatNullPointerException()
                .isThrownBy(() -> TwirpBundle.builder().pathPrefixes("/rpc", null).build());
        assertThatNullPointerException()
                .isThrownBy(() -> TwirpBundle.builder().pathPrefix(null).build());
    }

    private record Reply(int status, String body) {
    }

    private static final class TestServer implements AutoCloseable {
        private static final URI BASE_URI = URI.create("http://localhost/");
        private final ApplicationHandler application;

        TestServer(TwirpBundle<Configuration> bundle) {
            Environment environment = new Environment("twirp-bundle-test");
            bundle.run(new Configuration(), environment);
            environment.jersey().register(EchoResource.class);
            application = new ApplicationHandler(environment.jersey().getResourceConfig());
        }

        Reply post(String path, String json) throws Exception {
            ContainerRequest request = new ContainerRequest(
                    BASE_URI, BASE_URI.resolve(path), "POST", null, new MapPropertiesDelegate());
            request.getHeaders().putSingle(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON);
            request.getHeaders().putSingle(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON);
            request.setEntityStream(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
            ByteArrayOutputStream body = new ByteArrayOutputStream();

            ContainerResponse response = application.apply(request, body).get(10, TimeUnit.SECONDS);
            return new Reply(response.getStatus(), body.toString(StandardCharsets.UTF_8));
        }

        @Override
        public void close() {
            application.onShutdown(null);
        }
    }

    @Path("/twirp/test.Echo")
    public static class EchoResource {
        @POST
        @Path("/Echo")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.APPLICATION_JSON)
        public SourceContext echo(SourceContext request) {
            return request;
        }
    }
}

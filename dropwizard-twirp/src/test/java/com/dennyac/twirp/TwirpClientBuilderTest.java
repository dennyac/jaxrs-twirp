// Copyright 2026 the jaxrs-twirp authors
// SPDX-License-Identifier: Apache-2.0

package com.dennyac.twirp;

import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.WebTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link TwirpClientBuilder}'s wiring: wire-format selection, root
 * target resolution, and the guard rails around an unconfigured build.
 *
 * <p>These exercise the {@link TwirpClientBuilder#using(Client)} path with a real
 * JAX-RS client so no Dropwizard {@code Environment} is needed. The
 * environment + configuration path is covered end to end by the example's
 * {@code GeneratedClientIntegrationTest}.
 */
class TwirpClientBuilderTest {

    private Client client;

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
}

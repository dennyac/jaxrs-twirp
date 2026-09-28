// Copyright 2026 the jaxrs-twirp authors
// SPDX-License-Identifier: Apache-2.0

package com.dennyac.twirp.resteasy;

import com.dennyac.twirp.ErrorCode;
import com.dennyac.twirp.TwirpContext;
import com.dennyac.twirp.TwirpException;
import com.dennyac.twirp.TwirpMediaTypes;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

class TwirpResteasyClientTest {

    @RegisterExtension
    static final ResteasyServer SERVER = new ResteasyServer();

    private static final EchoMessage MESSAGE = EchoMessage.newBuilder()
            .setHatColor("red").setHatSize(7).addTags("wool").build();

    private static Client client;

    @BeforeAll
    static void createClient() {
        client = ClientBuilder.newClient();
    }

    @AfterAll
    static void closeClient() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void usesTheResteasyClient() {
        assertThat(client).isInstanceOf(ResteasyClient.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {TwirpMediaTypes.APPLICATION_PROTOBUF, TwirpMediaTypes.APPLICATION_JSON})
    void roundTrip(String contentType) {
        assertThat(echo(contentType).echo(MESSAGE, TwirpContext.empty())).isEqualTo(MESSAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {TwirpMediaTypes.APPLICATION_PROTOBUF, TwirpMediaTypes.APPLICATION_JSON})
    void serverErrorsBecomeTwirpExceptions(String contentType) {
        assertThatThrownBy(() -> echo(contentType).reject(MESSAGE, TwirpContext.empty()))
                .isInstanceOfSatisfying(TwirpException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
                    assertThat(e.getMessage()).isEqualTo("hat_size: must be positive");
                    assertThat(e.getMeta()).containsExactly(entry("argument", "hat_size"));
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {TwirpMediaTypes.APPLICATION_PROTOBUF, TwirpMediaTypes.APPLICATION_JSON})
    void outboundHeadersReachTheServer(String contentType) {
        Echo echo = echo(contentType);
        TwirpContext context = TwirpContext.ofOutboundHeaders(Map.of(
                "X-Request-ID", List.of("request-123"),
                "Authorization", List.of(BearerTokenFilter.AUTHORIZATION)));

        HeaderResponse header = echo.readHeader(
                HeaderRequest.newBuilder().setName("X-Request-ID").build(), context);
        WhoAmIResponse who = echo.whoAmI(WhoAmIRequest.getDefaultInstance(), context);

        assertThat(header.getValuesList()).containsExactly("request-123");
        assertThat(who.getSubject()).isEqualTo("alice");
    }

    private static Echo echo(String contentType) {
        return new EchoClient(client.target(SERVER.baseUri()), contentType);
    }
}

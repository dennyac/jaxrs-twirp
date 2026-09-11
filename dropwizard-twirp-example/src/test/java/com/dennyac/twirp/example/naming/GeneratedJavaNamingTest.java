// Copyright 2026 the jaxrs-twirp authors
// SPDX-License-Identifier: Apache-2.0

package com.dennyac.twirp.example.naming;

import com.dennyac.twirp.TwirpContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratedJavaNamingTest {

    @ParameterizedTest
    @MethodSource("generatedServices")
    void generatedServicesUseProtocMessageClasses(
            Class<?> service, Class<?> resource, Class<?> client, String method,
            Class<?> request, Class<?> response) throws NoSuchMethodException {
        assertThat(service.getMethod(method, request, TwirpContext.class).getReturnType())
                .isEqualTo(response);
        assertThat(client).isAssignableTo(service);
        assertThat(client.getMethod(method, request, TwirpContext.class).getReturnType())
                .isEqualTo(response);
        assertThat(resource.getMethod(method, request, HttpHeaders.class, SecurityContext.class).getReturnType())
                .isEqualTo(Response.class);
    }

    private static Stream<Arguments> generatedServices() {
        return Stream.of(
                Arguments.of(Haberdasher.class, HaberdasherResource.class, HaberdasherClient.class, "makeHat",
                        HaberdasherOuterClass.Size.class, HaberdasherOuterClass.Hat.class),
                Arguments.of(MessageNames.class, MessageNamesResource.class, MessageNamesClient.class, "echo",
                        MessageCollisionOuterClass.MessageCollision.class,
                        MessageCollisionOuterClass.MessageCollision.class),
                Arguments.of(EnumNames.class, EnumNamesResource.class, EnumNamesClient.class, "echo",
                        EnumCollisionOuterClass.Request.class, EnumCollisionOuterClass.Request.class),
                Arguments.of(NestedMessageNames.class, NestedMessageNamesResource.class, NestedMessageNamesClient.class,
                        "echo", NestedMessageCollisionOuterClass.Container.Inner.NestedMessageCollision.class,
                        NestedMessageCollisionOuterClass.Container.class),
                Arguments.of(NestedEnumNames.class, NestedEnumNamesResource.class, NestedEnumNamesClient.class,
                        "echo", NestedEnumCollisionOuterClass.Container.Inner.class,
                        NestedEnumCollisionOuterClass.Container.class),
                Arguments.of(NonCollidingNames.class, NonCollidingNamesResource.class, NonCollidingNamesClient.class,
                        "echo", RpcMessagesV2.Request.class, RpcMessagesV2.Request.class),
                Arguments.of(ExplicitNames.class, ExplicitNamesResource.class, ExplicitNamesClient.class, "echo",
                        ExplicitMessages.Explicit.class, ExplicitMessages.Explicit.class),
                Arguments.of(MultipleFiles.class, MultipleFilesResource.class, MultipleFilesClient.class, "echo",
                        MultipleRequest.class, MultipleReply.class));
    }
}

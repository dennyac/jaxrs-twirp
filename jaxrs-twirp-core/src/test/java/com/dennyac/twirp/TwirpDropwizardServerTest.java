// Copyright 2026 the jaxrs-twirp authors
// SPDX-License-Identifier: Apache-2.0

package com.dennyac.twirp;

import com.dennyac.twirp.testproto.Parcel;
import com.dennyac.twirp.testproto.Proto2Message;
import com.dennyac.twirp.testproto.TestMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs {@link TwirpServerFeature} alongside Dropwizard's default exception
 * mappers, whose catch-all mapper answers unexpected failures with
 * {@code {"code":500,"message":"There was an error processing your request. ..."}}.
 */
@ExtendWith(DropwizardExtensionsSupport.class)
class TwirpDropwizardServerTest {

    private static final String INTERNAL = "{\"code\":\"internal\",\"msg\":\"internal server error\"}";

    static final ResourceExtension RESOURCE = ResourceExtension.builder()
            .addProvider(new TwirpServerFeature())
            .addResource(new TwirpResource())
            .addResource(new RestResource())
            .build();

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void virtualMachineErrorsBecomeTwirpInternalErrors() {
        Response response = post("/twirp/test.Failing/Overflow", MediaType.APPLICATION_JSON);

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.readEntity(String.class)).isEqualTo(INTERNAL);
    }

    @Test
    void responseEncodingFailuresAreInternalRatherThanMalformed() {
        Response response = post("/twirp/test.Failing/Parcel", MediaType.APPLICATION_JSON);

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.readEntity(String.class)).isEqualTo(INTERNAL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"overflow", "corrupt-record"})
    void restFailuresKeepDropwizardErrors(String path) throws Exception {
        Response response = RESOURCE.target("/rest/" + path).request().get();

        assertThat(response.getStatus()).isEqualTo(500);
        JsonNode body = json.readTree(response.readEntity(String.class));
        assertThat(body.get("code").asInt()).isEqualTo(500);
        assertThat(body.get("message").asText())
                .startsWith("There was an error processing your request.");
    }

    @ParameterizedTest
    @ValueSource(strings = {TwirpMediaTypes.APPLICATION_PROTOBUF, TwirpMediaTypes.APPLICATION_JSON})
    void clientReportsResponsesMissingRequiredFieldsAsMalformed(String mediaType) {
        WebTarget target = RESOURCE.target("/twirp/test.Failing/Partial");
        TwirpClients.registerProviders(target);

        assertThatThrownBy(() -> TwirpClients.invoke(target.request(mediaType),
                Entity.entity(TestMessage.getDefaultInstance(), mediaType), Proto2Message.class))
                .isInstanceOfSatisfying(TwirpException.class, twirp -> {
                    assertThat(twirp.getErrorCode()).isEqualTo(ErrorCode.MALFORMED);
                    assertThat(twirp).hasMessageEndingWith("Message missing required fields: count");
                });
    }

    private static Response post(String path, String mediaType) {
        return RESOURCE.target(path).request(mediaType).post(Entity.entity("{}", mediaType));
    }

    @Path("/twirp/test.Failing")
    @Consumes({TwirpMediaTypes.APPLICATION_PROTOBUF, TwirpMediaTypes.APPLICATION_JSON})
    @Produces({TwirpMediaTypes.APPLICATION_PROTOBUF, TwirpMediaTypes.APPLICATION_JSON})
    public static class TwirpResource {
        @POST
        @Path("/Overflow")
        public TestMessage overflow(TestMessage request) {
            return TwirpInvocations.invoke("Overflow", () -> {
                throw new StackOverflowError();
            });
        }

        @POST
        @Path("/Parcel")
        public Parcel parcel(TestMessage request) {
            return Parcel.newBuilder().setContents(Any.pack(request)).build();
        }

        @POST
        @Path("/Partial")
        public Proto2Message partial(TestMessage request) {
            return Proto2Message.newBuilder().setName("fedora").buildPartial();
        }
    }

    @Path("/rest")
    @Produces(MediaType.APPLICATION_JSON)
    public static class RestResource {
        @GET
        @Path("/overflow")
        public String overflow() {
            throw new StackOverflowError();
        }

        @GET
        @Path("/corrupt-record")
        public String corruptRecord() throws InvalidProtocolBufferException {
            throw new InvalidProtocolBufferException("stored record is corrupt");
        }
    }
}

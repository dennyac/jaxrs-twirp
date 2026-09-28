// Copyright 2026 the jaxrs-twirp authors
// SPDX-License-Identifier: Apache-2.0

package com.dennyac.twirp.errors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;
import com.dennyac.twirp.ErrorCode;
import com.dennyac.twirp.TwirpException;
import com.dennyac.twirp.TwirpMediaTypes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link TwirpExceptionMapper} and
 * {@link InvalidProtocolBufferExceptionMapper} produce the wire format the
 * Twirp spec requires:
 *
 * <ul>
 *   <li>HTTP status matches {@link ErrorCode#httpStatus()}.</li>
 *   <li>Body is always {@code application/json}, regardless of request type.</li>
 *   <li>JSON has shape {@code {"code":..., "msg":..., "meta":{...}}} with
 *       {@code meta} omitted when empty.</li>
 * </ul>
 */
@ExtendWith(DropwizardExtensionsSupport.class)
class TwirpExceptionMapperTest {

    static final ResourceExtension RESOURCE = ResourceExtension.builder()
            .addProvider(TwirpExceptionMapper.class)
            .addProvider(InvalidProtocolBufferExceptionMapper.class)
            .addResource(new ThrowingResource())
            .build();

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void notFoundMapsTo404WithCorrectShape() throws Exception {
        Response response = RESOURCE.target("/boom/not_found").request().get();

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getMediaType().toString()).startsWith(MediaType.APPLICATION_JSON);

        JsonNode body = json.readTree(response.readEntity(String.class));
        assertThat(body.get("code").asText()).isEqualTo("not_found");
        assertThat(body.get("msg").asText()).isEqualTo("hat does not exist");
        assertThat(body.has("meta")).isFalse(); // empty meta is omitted
    }

    @Test
    void invalidArgumentIncludesMeta() throws Exception {
        Response response = RESOURCE.target("/boom/invalid_argument").request().get();

        assertThat(response.getStatus()).isEqualTo(400);
        JsonNode body = json.readTree(response.readEntity(String.class));

        assertThat(body.get("code").asText()).isEqualTo("invalid_argument");
        assertThat(body.get("msg").asText()).contains("inches");
        assertThat(body.get("meta").get("argument").asText()).isEqualTo("inches");
    }

    @Test
    void resourceExhaustedReturns429() throws Exception {
        Response response = RESOURCE.target("/boom/resource_exhausted").request().get();

        assertThat(response.getStatus()).isEqualTo(429);
        JsonNode body = json.readTree(response.readEntity(String.class));
        assertThat(body.get("code").asText()).isEqualTo("resource_exhausted");
    }

    @Test
    void internalReturns500WithMessage() throws Exception {
        Response response = RESOURCE.target("/boom/internal").request().get();

        assertThat(response.getStatus()).isEqualTo(500);
        JsonNode body = json.readTree(response.readEntity(String.class));
        assertThat(body.get("code").asText()).isEqualTo("internal");
    }

    @Test
    void invalidProtocolBufferExceptionMapsToMalformed() throws Exception {
        Response response = RESOURCE.target("/boom/malformed_proto").request().get();

        assertThat(response.getStatus()).isEqualTo(400);
        JsonNode body = json.readTree(response.readEntity(String.class));
        assertThat(body.get("code").asText()).isEqualTo("malformed");
    }

    @Test
    void responseIsJsonEvenWhenClientAsksForProtobuf() throws Exception {
        // Per the Twirp spec, error bodies are *always* JSON regardless of Accept.
        Response response = RESOURCE.target("/boom/not_found")
                .request(TwirpMediaTypes.APPLICATION_PROTOBUF)
                .get();

        assertThat(response.getMediaType().toString()).startsWith(MediaType.APPLICATION_JSON);
    }

    @Path("/boom")
    public static class ThrowingResource {

        @GET
        @Path("/{kind}")
        public String throwIt(@PathParam("kind") String kind) throws Exception {
            switch (kind) {
                case "not_found":
                    throw TwirpException.notFound("hat does not exist");
                case "invalid_argument":
                    throw TwirpException.invalidArgument("inches", "must be positive");
                case "resource_exhausted":
                    throw new TwirpException(ErrorCode.RESOURCE_EXHAUSTED, "rate limited");
                case "internal":
                    throw new TwirpException(ErrorCode.INTERNAL, "kaboom",
                            new RuntimeException("inner"));
                case "malformed_proto":
                    throw new com.google.protobuf.InvalidProtocolBufferException(
                            "synthetic malformed wire data");
                default:
                    return "ok";
            }
        }
    }
}

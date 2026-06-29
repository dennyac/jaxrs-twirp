package com.dennyac.twirp.codec;

import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;
import com.dennyac.twirp.TwirpMediaTypes;
import com.dennyac.twirp.testproto.TestMessage;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end wire-format tests for the protobuf and JSON body providers.
 *
 * <p>To exercise the providers exactly as the Twirp protocol uses them we send
 * the request body as raw bytes / raw JSON from the client side and parse the
 * response body the same way. That way the test exercises the providers in the
 * server's read-then-write path without needing matching providers on the
 * Jersey client.
 */
@ExtendWith(DropwizardExtensionsSupport.class)
class ProtobufCodecTest {

    static final ResourceExtension RESOURCE = ResourceExtension.builder()
            .addProvider(ProtobufMessageBodyReader.class)
            .addProvider(ProtobufMessageBodyWriter.class)
            .addProvider(ProtobufJsonMessageBodyReader.class)
            .addProvider(ProtobufJsonMessageBodyWriter.class)
            .addResource(new EchoResource())
            .build();

    @Test
    void protobufRoundTrip() throws Exception {
        TestMessage request = TestMessage.newBuilder()
                .setHatColor("red")
                .setHatSize(7)
                .addTags("wool")
                .addTags("warm")
                .build();

        byte[] requestBytes = request.toByteArray();

        Response response = RESOURCE.target("/echo")
                .request(TwirpMediaTypes.APPLICATION_PROTOBUF)
                .post(Entity.entity(requestBytes, TwirpMediaTypes.APPLICATION_PROTOBUF));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getMediaType()).isEqualTo(TwirpMediaTypes.APPLICATION_PROTOBUF_TYPE);

        byte[] responseBytes = response.readEntity(byte[].class);
        TestMessage echoed = TestMessage.parseFrom(responseBytes);
        assertThat(echoed).isEqualTo(request);
    }

    @Test
    void jsonRoundTripUsesSnakeCase() {
        String requestJson = "{\"hat_color\":\"red\",\"hat_size\":7,\"tags\":[\"wool\"]}";

        Response response = RESOURCE.target("/echo")
                .request(MediaType.APPLICATION_JSON)
                .post(Entity.entity(requestJson, MediaType.APPLICATION_JSON));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getMediaType().toString()).startsWith(MediaType.APPLICATION_JSON);

        String responseJson = response.readEntity(String.class);
        // Twirp default JSON uses proto field names (snake_case), not camelCase.
        assertThat(responseJson).contains("\"hat_color\":\"red\"");
        assertThat(responseJson).contains("\"hat_size\":7");
        assertThat(responseJson).doesNotContain("hatColor").doesNotContain("hatSize");
        assertThat(responseJson).contains("\"tags\":[\"wool\"]");
    }

    @Test
    void jsonReaderIgnoresUnknownFields() {
        String requestJson = "{\"hat_color\":\"red\",\"unknown\":\"oops\",\"hat_size\":2}";

        Response response = RESOURCE.target("/echo")
                .request(MediaType.APPLICATION_JSON)
                .post(Entity.entity(requestJson, MediaType.APPLICATION_JSON));

        assertThat(response.getStatus()).isEqualTo(200);
        String responseJson = response.readEntity(String.class);
        assertThat(responseJson).contains("\"hat_color\":\"red\"");
        assertThat(responseJson).contains("\"hat_size\":2");
    }

    @Test
    void jsonWriterEmitsDefaultValues() {
        String requestJson = "{}";

        Response response = RESOURCE.target("/echo")
                .request(MediaType.APPLICATION_JSON)
                .post(Entity.entity(requestJson, MediaType.APPLICATION_JSON));

        String responseJson = response.readEntity(String.class);
        // Twirp Go default is EmitUnpopulated: true — empty string / 0 / [] should appear.
        assertThat(responseJson).contains("\"hat_color\":\"\"");
        assertThat(responseJson).contains("\"hat_size\":0");
    }

    @Test
    void malformedProtobufFailsBeforeServiceMethod() {
        // 0xFF / random text is never valid proto wire data, so the body reader
        // should reject it before the resource method runs. Without the
        // InvalidProtocolBufferExceptionMapper registered here, Jersey may translate
        // this to a 500; we just assert that the request was rejected, not that
        // the resource method was reached.
        byte[] junk = "this is not a protobuf".getBytes(StandardCharsets.UTF_8);

        Response response = RESOURCE.target("/echo")
                .request(TwirpMediaTypes.APPLICATION_PROTOBUF)
                .post(Entity.entity(junk, TwirpMediaTypes.APPLICATION_PROTOBUF));

        assertThat(response.getStatus()).isGreaterThanOrEqualTo(400);
    }

    @Path("/echo")
    public static class EchoResource {
        @POST
        @Consumes({TwirpMediaTypes.APPLICATION_PROTOBUF, MediaType.APPLICATION_JSON})
        @Produces({TwirpMediaTypes.APPLICATION_PROTOBUF, MediaType.APPLICATION_JSON})
        public TestMessage echo(TestMessage in) {
            return in;
        }
    }
}

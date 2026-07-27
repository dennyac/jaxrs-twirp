package com.dennyac.twirp.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dropwizard.testing.ConfigOverride;
import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit5.DropwizardAppExtension;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import com.dennyac.twirp.example.haberdasher.Hat;
import com.dennyac.twirp.example.haberdasher.HatStyle;
import com.dennyac.twirp.example.haberdasher.Inventory;
import com.dennyac.twirp.example.haberdasher.InventoryRequest;
import com.dennyac.twirp.example.haberdasher.Size;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spins up the example application on a random port and exercises the
 * generated Twirp endpoints over real HTTP.
 *
 * <p>Covers all four "spec-shaped" scenarios: protobuf round-trip, JSON
 * round-trip (snake_case), business-logic errors from the impl, and malformed
 * wire data.
 */
@ExtendWith(DropwizardExtensionsSupport.class)
class ExampleApplicationIntegrationTest {

    private static final DropwizardAppExtension<ExampleConfiguration> APP =
            new DropwizardAppExtension<>(
                    ExampleApplication.class,
                    ResourceHelpers.resourceFilePath("example.yml"),
                    ConfigOverride.config("server.applicationConnectors[0].port", "0"),
                    ConfigOverride.config("server.adminConnectors[0].port", "0"));

    private static final String MAKE_HAT_PATH =
            "/twirp/twitch.twirp.example.haberdasher.Haberdasher/MakeHat";

    private static final String LIST_INVENTORY_PATH =
            "/twirp/twitch.twirp.example.haberdasher.Haberdasher/ListInventory";

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void protobufRoundTrip() throws Exception {
        Size request = Size.newBuilder().setInches(7).build();

        HttpResponse<byte[]> response = http.send(
                HttpRequest.newBuilder(uri(MAKE_HAT_PATH))
                        .header("Content-Type", "application/protobuf")
                        .header("Accept", "application/protobuf")
                        .POST(BodyPublishers.ofByteArray(request.toByteArray()))
                        .build(),
                BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValue("application/protobuf");
        Hat hat = Hat.parseFrom(response.body());
        assertThat(hat.getInches()).isEqualTo(7);
        assertThat(hat.getStyleName()).isEqualTo("bowler");
        assertThat(hat.getColor()).isIn(Set.of("red", "blue", "green", "black", "brown"));
    }

    @Test
    void jsonRoundTripUsesSnakeCaseFieldNames() throws Exception {
        // 12 inches -> fedora per HaberdasherImpl.
        String body = "{\"inches\":12}";

        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(uri(MAKE_HAT_PATH))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(),
                BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(ct -> assertThat(ct).startsWith("application/json"));

        JsonNode node = mapper.readTree(response.body());
        assertThat(node.get("inches").asInt()).isEqualTo(12);
        assertThat(node.get("style_name").asText()).isEqualTo("fedora");
        // Default printer includes default values — color is always present,
        // even when empty, because of TwirpBundle's defaultPrinter().
        assertThat(node.has("color")).isTrue();
    }

    @Test
    void responseContentTypeMirrorsRequestEvenWithoutAcceptHeader() throws Exception {
        // Per Twirp v7, the response Content-Type mirrors the request
        // Content-Type — not the Accept header. Curl-style clients that send
        // 'Content-Type: application/json' without an Accept header should
        // still get JSON back, not whatever happens to be first in @Produces.
        String body = "{\"inches\":9}";

        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(uri(MAKE_HAT_PATH))
                        .header("Content-Type", "application/json")
                        .POST(BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(),
                BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(ct -> assertThat(ct).startsWith("application/json"));
        // Bowler at 9", proof we actually parsed the request as JSON.
        JsonNode node = mapper.readTree(response.body());
        assertThat(node.get("inches").asInt()).isEqualTo(9);
        assertThat(node.get("style_name").asText()).isEqualTo("bowler");
    }

    @Test
    void businessLogicErrorsAreEmittedAsTwirpJson() throws Exception {
        // inches=0 violates the impl's precondition; should surface as a
        // structured Twirp error regardless of the request content type.
        Size request = Size.newBuilder().setInches(0).build();

        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(uri(MAKE_HAT_PATH))
                        .header("Content-Type", "application/protobuf")
                        .POST(BodyPublishers.ofByteArray(request.toByteArray()))
                        .build(),
                BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(ct -> assertThat(ct).startsWith("application/json"));

        JsonNode node = mapper.readTree(response.body());
        assertThat(node.get("code").asText()).isEqualTo("invalid_argument");
        assertThat(node.get("msg").asText()).isEqualTo("inches must be > 0");
        assertThat(node.get("meta").get("argument").asText()).isEqualTo("inches");
    }

    @Test
    void malformedWireBytesProduceMalformedTwirpError() throws Exception {
        // Random bytes that don't decode as a valid Size protobuf.
        byte[] garbage = new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};

        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(uri(MAKE_HAT_PATH))
                        .header("Content-Type", "application/protobuf")
                        .POST(BodyPublishers.ofByteArray(garbage))
                        .build(),
                BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode node = mapper.readTree(response.body());
        assertThat(node.get("code").asText()).isEqualTo("malformed");
        assertThat(node.get("msg").asText()).contains("decode");
    }

    @Test
    void malformedJsonProducesMalformedTwirpError() throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(uri(MAKE_HAT_PATH))
                        .header("Content-Type", "application/json")
                        .POST(BodyPublishers.ofString("{ this is not valid json", StandardCharsets.UTF_8))
                        .build(),
                BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode node = mapper.readTree(response.body());
        assertThat(node.get("code").asText()).isEqualTo("malformed");
    }

    @Test
    void bothWireFormatsCoexistOnTheSameResource() throws Exception {
        // The generated HaberdasherResource has ONE method per RPC with
        // @Consumes({protobuf, json}) and @Produces({protobuf, json}). This
        // test interleaves both formats against the same endpoint to prove
        // they share a single resource instance — no separate /protobuf or
        // /json sub-routes, no second listener, no per-format service.
        URI endpoint = uri(MAKE_HAT_PATH);

        // 1) JSON in -> JSON out.
        HttpResponse<String> jsonResponse = http.send(
                HttpRequest.newBuilder(endpoint)
                        .header("Content-Type", "application/json")
                        .POST(BodyPublishers.ofString("{\"inches\":12}", StandardCharsets.UTF_8))
                        .build(),
                BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(jsonResponse.statusCode()).isEqualTo(200);
        assertThat(jsonResponse.headers().firstValue("Content-Type"))
                .hasValueSatisfying(ct -> assertThat(ct).startsWith("application/json"));
        JsonNode jsonBody = mapper.readTree(jsonResponse.body());
        assertThat(jsonBody.get("inches").asInt()).isEqualTo(12);
        assertThat(jsonBody.get("style_name").asText()).isEqualTo("fedora");

        // 2) Protobuf in -> protobuf out, same JVM, same listener, same path.
        HttpResponse<byte[]> protoResponse = http.send(
                HttpRequest.newBuilder(endpoint)
                        .header("Content-Type", "application/protobuf")
                        .POST(BodyPublishers.ofByteArray(
                                Size.newBuilder().setInches(7).build().toByteArray()))
                        .build(),
                BodyHandlers.ofByteArray());

        assertThat(protoResponse.statusCode()).isEqualTo(200);
        assertThat(protoResponse.headers().firstValue("Content-Type"))
                .hasValue("application/protobuf");
        Hat protoHat = Hat.parseFrom(protoResponse.body());
        assertThat(protoHat.getInches()).isEqualTo(7);
        assertThat(protoHat.getStyleName()).isEqualTo("bowler");

        // 3) Back to JSON to prove the protobuf request didn't poison state.
        HttpResponse<String> jsonAgain = http.send(
                HttpRequest.newBuilder(endpoint)
                        .header("Content-Type", "application/json")
                        .POST(BodyPublishers.ofString("{\"inches\":12}", StandardCharsets.UTF_8))
                        .build(),
                BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(jsonAgain.statusCode()).isEqualTo(200);
        assertThat(jsonAgain.headers().firstValue("Content-Type"))
                .hasValueSatisfying(ct -> assertThat(ct).startsWith("application/json"));
        assertThat(mapper.readTree(jsonAgain.body()).get("style_name").asText())
                .isEqualTo("fedora");
    }

    @Test
    void listInventoryJsonExposesEnumsArraysAndMaps() throws Exception {
        // Filter to fedoras. This RPC's response carries an enum, a repeated
        // nested message, and a map — the three proto shapes whose JSON form is
        // most distinct from the protobuf wire form. We assert the JSON document
        // structure directly so the snake_case + enum-as-string + array + object
        // mapping is visible.
        String body = "{\"style\":\"FEDORA\"}";

        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(uri(LIST_INVENTORY_PATH))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(),
                BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(ct -> assertThat(ct).startsWith("application/json"));

        JsonNode node = mapper.readTree(response.body());

        // repeated StockItem -> JSON array of objects.
        JsonNode items = node.get("items");
        assertThat(items.isArray()).isTrue();
        assertThat(items).hasSize(2);
        // enum -> JSON string (the name, not the integer tag).
        assertThat(items.get(0).get("style").asText()).isEqualTo("FEDORA");
        assertThat(items.get(0).get("inches").asInt()).isEqualTo(11);
        assertThat(items.get(1).get("inches").asInt()).isEqualTo(12);

        // map<string,int32> -> JSON object, snake_case field name preserved.
        JsonNode countByColor = node.get("count_by_color");
        assertThat(countByColor.isObject()).isTrue();
        assertThat(countByColor.get("grey").asInt()).isEqualTo(1);
        assertThat(countByColor.get("black").asInt()).isEqualTo(1);
    }

    @Test
    void listInventoryProtobufRoundTripCarriesEnumsArraysAndMaps() throws Exception {
        // Same RPC, protobuf wire format. Proves the richer message round-trips
        // as binary against the very same generated resource.
        InventoryRequest request = InventoryRequest.newBuilder()
                .setStyle(HatStyle.BOWLER)
                .build();

        HttpResponse<byte[]> response = http.send(
                HttpRequest.newBuilder(uri(LIST_INVENTORY_PATH))
                        .header("Content-Type", "application/protobuf")
                        .header("Accept", "application/protobuf")
                        .POST(BodyPublishers.ofByteArray(request.toByteArray()))
                        .build(),
                BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValue("application/protobuf");

        Inventory inventory = Inventory.parseFrom(response.body());
        assertThat(inventory.getItemsList()).hasSize(2);
        assertThat(inventory.getItemsList())
                .allSatisfy(item -> assertThat(item.getStyle()).isEqualTo(HatStyle.BOWLER));
        assertThat(inventory.getCountByColorMap())
                .containsEntry("black", 1)
                .containsEntry("brown", 1);
    }

    @Test
    void unknownUrlReturnsBadRoute() throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(uri("/twirp/twitch.twirp.example.haberdasher.Haberdasher/Nope"))
                        .header("Content-Type", "application/json")
                        .POST(BodyPublishers.ofString("{\"inches\":7}", StandardCharsets.UTF_8))
                        .build(),
                BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(404);
        JsonNode node = mapper.readTree(response.body());
        assertThat(node.get("code").asText()).isEqualTo("bad_route");
    }

    @Test
    void nonPostMethodReturnsBadRoute() throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(uri(MAKE_HAT_PATH))
                        .header("Content-Type", "application/json")
                        .GET()
                        .build(),
                BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(404);
        JsonNode node = mapper.readTree(response.body());
        assertThat(node.get("code").asText()).isEqualTo("bad_route");
    }

    @Test
    void unsupportedContentTypeReturnsBadRoute() throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(uri(MAKE_HAT_PATH))
                        .header("Content-Type", "text/plain")
                        .POST(BodyPublishers.ofString("hello", StandardCharsets.UTF_8))
                        .build(),
                BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(404);
        JsonNode node = mapper.readTree(response.body());
        assertThat(node.get("code").asText()).isEqualTo("bad_route");
    }

    @Test
    void unrelatedRestNotFoundKeepsItsNormalStatusAndBody() throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(uri("/api/missing"))
                        .GET()
                        .build(),
                BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).doesNotContain("\"code\":\"bad_route\"");
    }

    @Test
    void unrelatedRestMethodNotAllowedStays405() throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(uri("/api/greeting"))
                        .method("DELETE", BodyPublishers.noBody())
                        .build(),
                BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.body()).doesNotContain("\"code\":\"bad_route\"");
    }

    @Test
    void unrelatedRestUnsupportedMediaTypeStays415() throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(uri("/api/greeting"))
                        .header("Content-Type", "text/plain")
                        .POST(BodyPublishers.ofString("hello", StandardCharsets.UTF_8))
                        .build(),
                BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(415);
        assertThat(response.body()).doesNotContain("\"code\":\"bad_route\"");
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + APP.getLocalPort() + path);
    }
}

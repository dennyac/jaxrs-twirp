# dropwizard-twirp-example

A runnable Dropwizard 5 application based on the [Haberdasher][] Twirp service.
It demonstrates generated resources and clients, both wire formats, and
bearer-token authentication. For an overview of the framework-neutral runtime,
see [jaxrs-twirp][overview].

## What's in here

| File | Purpose |
| --- | --- |
| [haberdasher.proto][schema] | `MakeHat`, `ListInventory`, and secured `WhoAmI` RPCs |
| [ExampleApplication.java][application] | Bundle, resource, and authentication registration |
| [HaberdasherImpl.java][implementation] | Service implementation |
| [ExampleRestResource.java][rest] | Ordinary REST endpoints alongside Twirp |
| [example.yml][configuration] | Server configuration |

## Code generation

The [example POM][pom] enables `context=true`. Its `compile` goal generates
message classes, `Haberdasher`, `HaberdasherResource`, and `HaberdasherClient`
under `target/generated-sources/protobuf/java`. Every service method receives
a trailing `TwirpContext`; `WhoAmI` uses it to read the authenticated principal.

See the generator guide for [Maven setup][maven], [raw protoc][raw-protoc], and
[all options][options], including client-only and server-only output. For
Dropwizard-managed clients, see [`TwirpClientBuilder`][managed-client].

## Running the server

With JDK 17+ and Maven 3.9+, run from the repository root:

```bash
mvn -pl jaxrs-twirp-protoc,dropwizard-twirp-example -am install
java -jar dropwizard-twirp-example/target/dropwizard-twirp-example-0.1.0-SNAPSHOT.jar \
  server dropwizard-twirp-example/src/main/resources/example.yml
```

The application listens on `http://localhost:8080`; the admin connector uses
`http://localhost:8081`. Run the following requests in another terminal.

## Calling the service

### JSON

```bash
curl -s -H 'Content-Type: application/json' \
  -d '{"inches": 12}' \
  http://localhost:8080/twirp/twitch.twirp.example.haberdasher.Haberdasher/MakeHat
```

The color is selected randomly. One possible response is:

```json
{"inches":12,"color":"blue","style_name":"fedora"}
```

### Binary protobuf

Send the two bytes encoding `Size{inches=12}` to the same endpoint:

```bash
printf '\010\014' \
  | curl -s --data-binary @- \
      -H 'Content-Type: application/protobuf' \
      http://localhost:8080/twirp/twitch.twirp.example.haberdasher.Haberdasher/MakeHat \
  | xxd
```

The response is a binary `Hat`. Both formats use the same resource; the
request's `Content-Type` selects the successful response format.

### Trigger a server-side error

```bash
curl -i -H 'Content-Type: application/json' \
  -d '{"inches": -1}' \
  http://localhost:8080/twirp/twitch.twirp.example.haberdasher.Haberdasher/MakeHat
```

The response is HTTP 400 with a JSON body:

```json
{"code":"invalid_argument","msg":"inches must be > 0","meta":{"argument":"inches"}}
```

Errors use JSON even for protobuf requests. See the core's [error handling][errors].

### Richer messages: enums, arrays, and maps in JSON

`ListInventory` accepts an enum and returns repeated messages and a map:

```bash
curl -s -H 'Content-Type: application/json' \
  -d '{"style": "FEDORA"}' \
  http://localhost:8080/twirp/twitch.twirp.example.haberdasher.Haberdasher/ListInventory
```

Response, formatted for readability:

```json
{
  "items": [
    {"style": "FEDORA", "inches": 11, "color": "grey"},
    {"style": "FEDORA", "inches": 12, "color": "black"}
  ],
  "count_by_color": {"grey": 1, "black": 1}
}
```

Enums use their names in JSON, repeated messages become arrays, and maps become
objects. Field names such as `count_by_color` follow the core's [JSON defaults][json].
Omit `style` or use `HAT_STYLE_UNSPECIFIED` to list all stock. See the
[proto limitations][limitations] for editions, `Any`, and proto2 behavior.

## Request context and auth

`HaberdasherImpl` reads the principal and role from `TwirpContext`:

```java
@Override
public WhoAmIResponse whoAmI(WhoAmIRequest request, TwirpContext context) {
    String subject = context.principal().map(Principal::getName).orElse("");
    return WhoAmIResponse.newBuilder()
            .setSubject(subject)
            .setAdmin(context.isUserInRole("admin"))
            .build();
}
```

Generated methods have no Dropwizard `@Auth` parameter. [ExampleApplication][application]
creates an `OAuthCredentialAuthFilter` and binds it to `WhoAmI`:

```java
environment.jersey().register(TwirpAuthFeature.forRpcs(
        authFilter,
        HaberdasherResource.class,
        "WhoAmI"));
```

The filter sets the JAX-RS `SecurityContext`, which the resource passes to the
implementation through `TwirpContext`. It returns a Twirp `unauthenticated`
error when credentials are missing or invalid.

The example uses these hard-coded demo tokens:

| Token | Principal | Admin role |
| --- | --- | --- |
| `admin-token` | `admin` | Yes |
| `user-token` | `alice` | No |

```bash
curl -s -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer admin-token' -d '{}' \
  http://localhost:8080/twirp/twitch.twirp.example.haberdasher.Haberdasher/WhoAmI
```

To call it from Java, use the generated example classes and a JAX-RS client
implementation. Select the outbound authorization header explicitly:

```java
try (Client http = ClientBuilder.newClient()) {
    Haberdasher client = new HaberdasherClient(http.target("http://localhost:8080"));
    TwirpContext context = TwirpContext.ofOutboundHeaders(
            Map.of("Authorization", List.of("Bearer admin-token")));
    WhoAmIResponse me = client.whoAmI(WhoAmIRequest.getDefaultInstance(), context);
    System.out.println(me.getSubject());
}
```

Generated classes are in `com.dennyac.twirp.example.haberdasher`; `Client` and
`ClientBuilder` are from `jakarta.ws.rs.client`, `TwirpContext` is from
`com.dennyac.twirp`, and `Map`/`List` are from `java.util`.
Use `TwirpContext.empty()` for calls without metadata. See the core's
[context guide][context] for header forwarding rules and auth registration.

## Tests

After the build above, run from the repository root:

```bash
mvn -pl dropwizard-twirp-example test
```

[ExampleApplicationIntegrationTest][http-tests] covers raw HTTP, both wire
formats, errors, and REST/Twirp routing.
[GeneratedClientIntegrationTest][client-tests] covers generated clients,
managed-client construction, context headers, and authentication.

[Haberdasher]: https://github.com/twitchtv/twirp/blob/main/example/service.proto
[overview]: https://github.com/dennyac/jaxrs-twirp/blob/main/README.md
[schema]: https://github.com/dennyac/jaxrs-twirp/blob/main/dropwizard-twirp-example/src/main/proto/haberdasher.proto
[application]: https://github.com/dennyac/jaxrs-twirp/blob/main/dropwizard-twirp-example/src/main/java/com/dennyac/twirp/example/ExampleApplication.java
[implementation]: https://github.com/dennyac/jaxrs-twirp/blob/main/dropwizard-twirp-example/src/main/java/com/dennyac/twirp/example/HaberdasherImpl.java
[rest]: https://github.com/dennyac/jaxrs-twirp/blob/main/dropwizard-twirp-example/src/main/java/com/dennyac/twirp/example/ExampleRestResource.java
[configuration]: https://github.com/dennyac/jaxrs-twirp/blob/main/dropwizard-twirp-example/src/main/resources/example.yml
[pom]: https://github.com/dennyac/jaxrs-twirp/blob/main/dropwizard-twirp-example/pom.xml
[maven]: https://github.com/dennyac/jaxrs-twirp/blob/main/jaxrs-twirp-protoc/README.md#maven
[raw-protoc]: https://github.com/dennyac/jaxrs-twirp/blob/main/jaxrs-twirp-protoc/README.md#raw-protoc
[options]: https://github.com/dennyac/jaxrs-twirp/blob/main/jaxrs-twirp-protoc/README.md#generator-options
[managed-client]: https://github.com/dennyac/jaxrs-twirp/blob/main/dropwizard-twirp/README.md#twirpclientbuilder
[errors]: https://github.com/dennyac/jaxrs-twirp/blob/main/jaxrs-twirp-core/README.md#errors
[json]: https://github.com/dennyac/jaxrs-twirp/blob/main/jaxrs-twirp-core/README.md#json-configuration
[limitations]: https://github.com/dennyac/jaxrs-twirp/blob/main/jaxrs-twirp-protoc/README.md#proto-support-and-limitations
[context]: https://github.com/dennyac/jaxrs-twirp/blob/main/jaxrs-twirp-core/README.md#request-context-and-auth
[http-tests]: https://github.com/dennyac/jaxrs-twirp/blob/main/dropwizard-twirp-example/src/test/java/com/dennyac/twirp/example/ExampleApplicationIntegrationTest.java
[client-tests]: https://github.com/dennyac/jaxrs-twirp/blob/main/dropwizard-twirp-example/src/test/java/com/dennyac/twirp/example/GeneratedClientIntegrationTest.java

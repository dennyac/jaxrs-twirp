# dropwizard-twirp-example

A minimal, runnable Dropwizard 5 application that exposes the canonical
[Haberdasher][] Twirp service over HTTP. It's the reference for everything in
the parent [`jaxrs-twirp`](../README.md) repo, but the **primary thing
this module exists to demonstrate is code generation** — how you go from a
`.proto` to a JAX-RS resource you can register with `environment.jersey()` and
a Jersey client your other services can call.

If you've never seen Twirp before, the parent README has a short
["What is Twirp?"](../README.md#what-is-twirp) intro — read that first.

## What's in here

```
src/main/proto/haberdasher.proto                    # schema, including secured WhoAmI
src/main/java/.../ExampleApplication.java           # bundle + dropwizard-auth wiring
src/main/java/.../ExampleRestResource.java           # ordinary REST coexistence
src/main/java/.../HaberdasherImpl.java              # business logic (server side)
src/main/resources/example.yml                      # dropwizard config
src/test/java/.../ExampleApplicationIntegrationTest.java  # raw HTTP (curl-equivalent)
src/test/java/.../GeneratedClientIntegrationTest.java     # generated client → live server
```

`haberdasher.proto` is the **only** thing you hand-write that's protocol-shaped.
Everything else either depends on it (your impl, your tests) or is generated
from it. This module enables `context=true` and uses `TwirpContext` to carry
authentication into the secured `WhoAmI` RPC.

## Code generation

The example's [`pom.xml`](pom.xml) wires `jaxrs-twirp-protoc` into
[`protobuf-maven-plugin`][protobuf-maven-plugin] as a `<protocPlugin>`. Every
`mvn compile` (re)generates protobuf message classes **and** Twirp stubs into
`target/generated-sources/protobuf/java/`:

```xml
<plugin>
  <groupId>org.xolstice.maven.plugins</groupId>
  <artifactId>protobuf-maven-plugin</artifactId>
  <extensions>true</extensions>
  <executions>
    <execution>
      <goals><goal>compile</goal></goals>
      <configuration>
        <protocPlugins>
          <protocPlugin>
            <id>twirp_java</id>
            <groupId>com.dennyac.twirp</groupId>
            <artifactId>jaxrs-twirp-protoc</artifactId>
            <version>${project.version}</version>
            <mainClass>com.dennyac.twirp.protoc.Main</mainClass>
            <args>
              <arg>context=true</arg>
            </args>
          </protocPlugin>
        </protocPlugins>
      </configuration>
    </execution>
  </executions>
</plugin>
```

Nothing to install, no extra build step, IDE indexing works out of the box.

### What each generated file does

By default the plugin emits three Twirp files alongside protoc's message
classes:

| Generated file               | Role                                                                                  |
| ---------------------------- | ------------------------------------------------------------------------------------- |
| `Hat.java`, `Size.java`, …   | Protoc's built-in Java message classes (you'd get these from `protoc` anyway).        |
| `Haberdasher.java`           | The service interface — your impl (`HaberdasherImpl`) implements it.                  |
| `HaberdasherResource.java`   | JAX-RS resource bound to `/twirp/twitch.twirp.example.haberdasher.Haberdasher/*`. Registered by `ExampleApplication.run()`. |
| `HaberdasherClient.java`     | JAX-RS client implementing `Haberdasher`. Constructor takes a `WebTarget`. Pure Jakarta REST — no Dropwizard dependency, so it drops into any JAX-RS app. |

The client's transport is whatever JAX-RS `Client` you wrap. In a Dropwizard
app, [`dropwizard-client`][dw-client]'s `JerseyClientBuilder` gives you
metrics, Apache HttpClient, and `JerseyClientConfiguration` for timeouts —
[`GeneratedClientIntegrationTest`][gen-client-test] shows exactly that wiring.

For a less footgun-prone construction, the runtime `TwirpClientBuilder` folds
root-`WebTarget` resolution and wire-format selection into one fluent call (also
demonstrated in that test):

```java
Haberdasher hats = TwirpClientBuilder.forService(HaberdasherClient::new)
        .using(environment, new JerseyClientConfiguration())
        .baseUri("https://hats.example.com")
        .json()              // or .protobuf() (default)
        .build();
```

See the [`dropwizard-twirp` README](../dropwizard-twirp/README.md#twirpclientbuilder)
for the optional `clientBuilder=true` codegen flag that emits
`HaberdasherClient.builder(...)` sugar directly on the client.

### Asymmetric setups: client-only or server-only

Sometimes you want to split client and server across modules — the canonical
pattern is publishing a thin client jar that your other apps depend on, while
the server-only deploy lives in a separate module:

```
my-app-api/      # protos + 'server=false' → published as a thin client jar
my-app-server/   # depends on my-app-api, generates 'client=false' → deployed
```

Pass either option through `<args>` on the `<protocPlugin>` element:

```xml
<!-- in my-app-api/pom.xml: only emit the interface + JAX-RS client -->
<protocPlugin>
  <id>twirp_java</id>
  ...
  <args><arg>server=false</arg></args>
</protocPlugin>

<!-- in my-app-server/pom.xml: only emit the JAX-RS resource (interface comes from -api) -->
<protocPlugin>
  <id>twirp_java</id>
  ...
  <args><arg>client=false</arg></args>
</protocPlugin>
```

(Setting both to `false` is rejected — the only thing left to emit is the
service interface, which is rarely what you want and is more honestly
expressed as "I want protoc-built java messages, not Twirp".)

### Not using Maven?

`jaxrs-twirp-protoc` is a regular `protoc` plugin — the Maven wiring is
a convenience. Gradle, Bazel, or a `protoc` shell invocation drive the same
shaded jar via a one-line shim; see the parent README's
[code-generation-without-Maven section][raw-protoc] for the recipe and full
list of plugin options.

## Running the server

```bash
# Build once. mvn package shades a runnable fat jar via maven-shade-plugin
# (see pom.xml) so `java -jar …` works out of the box.
$ mvn -pl dropwizard-twirp-example -am install -DskipTests
$ cd dropwizard-twirp-example

# Start the server.
$ java -jar target/dropwizard-twirp-example-*.jar server src/main/resources/example.yml
# app  → http://localhost:8080
# admin→ http://localhost:8081
```

Or during development:

```bash
$ mvn exec:java -Dexec.mainClass=com.dennyac.twirp.example.ExampleApplication \
                -Dexec.args="server src/main/resources/example.yml"
```

## Smoke-testing with curl (both wire formats from one resource)

A Twirp service speaks **two interchangeable wire formats** on the same URL:
`application/protobuf` and `application/json`. The generated resource declares
both in `@Consumes`/`@Produces`, and `TwirpBundle` registers a message-body
reader/writer for each. The client picks the format via `Content-Type`; the
server echoes that choice in its response `Content-Type` (per Twirp v7 — no
`Accept` header required).

**JSON in, JSON out:**

```bash
$ curl -s -H 'Content-Type: application/json' \
       -d '{"inches": 12}' \
       http://localhost:8080/twirp/twitch.twirp.example.haberdasher.Haberdasher/MakeHat
{
  "inches": 12,
  "color": "blue",
  "style_name": "fedora"
}
```

**Protobuf in, protobuf out — same URL, same JVM, same resource instance:**

```bash
$ python -c 'import sys; sys.stdout.buffer.write(b"\x08\x0c")' \
  | curl -s --data-binary @- \
         -H 'Content-Type: application/protobuf' \
         http://localhost:8080/twirp/twitch.twirp.example.haberdasher.Haberdasher/MakeHat \
  | xxd
00000000: 080c 1203 7265 641a 0666 6564 6f72 61    ....red..fedora
```

(`\x08\x0c` is a serialized `Size{inches=12}` — protobuf field 1 (varint),
value 12. The response is a `Hat` with `inches=12`, a random color, and
`style_name=fedora`; the color string in your shell's hex output will
vary across requests.)

Note the URL shape: `/twirp/<proto-package>.<ServiceName>/<RpcName>`. That's
the Twirp v7 routing spec — every Twirp service uses it, regardless of wire
format.

The `bothWireFormatsCoexistOnTheSameResource` test in
[`ExampleApplicationIntegrationTest`](src/test/java/com/dennyac/twirp/example/ExampleApplicationIntegrationTest.java)
interleaves JSON → protobuf → JSON against the same listener to prove
this isn't an accident.

### Trigger a server-side error

Same endpoint, deliberate bad input:

```bash
$ curl -i -H 'Content-Type: application/json' \
       -d '{"inches": -1}' \
       http://localhost:8080/twirp/twitch.twirp.example.haberdasher.Haberdasher/MakeHat
HTTP/1.1 400 Bad Request
Content-Type: application/json

{
  "code": "invalid_argument",
  "msg": "inches must be > 0",
  "meta": {"argument": "inches"}
}
```

Twirp errors are *always* JSON regardless of the request content type. That's
the spec, and it's what lets thin clients decode errors without protobuf
descriptors.

### Richer messages: enums, arrays, and maps in JSON

`MakeHat` is deliberately flat. The second RPC, `ListInventory`, returns a
message with the three proto shapes whose JSON form differs most from protobuf:
an **enum** (`HatStyle`), a **`repeated` nested message** (`StockItem`), and a
**`map<string, int32>`** (`count_by_color`). No generator changes are needed for
any of this — protoc's Java codegen plus `JsonFormat` handle it — which is the
point of showing it.

```bash
$ curl -s -H 'Content-Type: application/json' \
       -d '{"style": "FEDORA"}' \
       http://localhost:8080/twirp/twitch.twirp.example.haberdasher.Haberdasher/ListInventory
{
  "items": [
    {"style": "FEDORA", "inches": 11, "color": "grey"},
    {"style": "FEDORA", "inches": 12, "color": "black"}
  ],
  "count_by_color": {"grey": 1, "black": 1}
}
```

Things to notice in the JSON:

- the enum serializes to its **name** (`"FEDORA"`), not its integer tag (`2`) —
  on the protobuf wire it's the tag instead;
- `items` is a JSON **array of objects** (the `repeated StockItem`);
- the map field keeps its **snake_case** key `count_by_color`, because the Twirp
  default printer preserves proto field names (map-entry key order is not
  significant).

Send the same request with `Content-Type: application/protobuf` and you get the
identical data as packed protobuf bytes — `ListInventory` is asserted over both
formats in
[`ExampleApplicationIntegrationTest`](src/test/java/com/dennyac/twirp/example/ExampleApplicationIntegrationTest.java).
Leave `style` out (or set it to `HAT_STYLE_UNSPECIFIED`) to list the whole shop.

See the top-level [Supported proto features & limitations][features] table for
the complete matrix of what generation handles, what needs configuration
(e.g. `google.protobuf.Any` over JSON), and what's out of scope (streaming).

<a id="request-context--auth"></a>

## Request context and auth

The example generates with `context=true`, so every method takes a trailing
`TwirpContext`. The hat RPCs ignore it; `WhoAmI` reads its authenticated
principal and role:

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

Dropwizard's `@Auth` injection only applies to resource methods that declare an
`@Auth` parameter. Generated Twirp methods do not, so `ExampleApplication`
binds an `OAuthCredentialAuthFilter` to the generated proto RPC with:

```java
environment.jersey().register(TwirpAuthFeature.forRpcs(
        authFilter,
        HaberdasherResource.class,
        "WhoAmI"));
```

`TwirpAuthFeature` validates the RPC name against the generated method's
`@Path` during startup. The filter then populates the JAX-RS `SecurityContext`,
which the generated resource places in `TwirpContext`.

The demo recognizes two bearer tokens:

```bash
# principal "admin", admin role
curl -s -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer admin-token' -d '{}' \
  http://localhost:8080/twirp/twitch.twirp.example.haberdasher.Haberdasher/WhoAmI

# principal "alice", no admin role
curl -s -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer user-token' -d '{}' \
  http://localhost:8080/twirp/twitch.twirp.example.haberdasher.Haberdasher/WhoAmI
```

Generated clients send explicit context headers:

```java
TwirpContext context = TwirpContext.ofOutboundHeaders(
        Map.of("Authorization", List.of("Bearer admin-token")));
WhoAmIResponse me = client.whoAmI(WhoAmIRequest.getDefaultInstance(), context);
```

Use `TwirpContext.empty()` when an RPC has no metadata to send.

## Tests

```bash
$ mvn -pl dropwizard-twirp-example test
```

Two suites:

| Suite                                | What it covers                                                                                                          |
| ------------------------------------ | ----------------------------------------------------------------------------------------------------------------------- |
| `ExampleApplicationIntegrationTest`  | Raw HTTP roundtrips — protobuf and JSON, success and errors, both formats interleaved, and REST/Twirp routing failures coexisting. |
| `GeneratedClientIntegrationTest`     | The generated `HaberdasherClient` against the live server, including JSON mode, managed-client construction, error decoding, context header forwarding, and dropwizard-auth principal/role handling. |

[Haberdasher]: https://github.com/twitchtv/twirp/blob/main/example/service.proto
[protobuf-maven-plugin]: https://www.xolstice.org/protobuf-maven-plugin/
[gen-client-test]: src/test/java/com/dennyac/twirp/example/GeneratedClientIntegrationTest.java
[dw-client]: https://www.dropwizard.io/en/stable/manual/client.html
[raw-protoc]: ../README.md#code-generation-without-maven-raw-protoc-plugin
[features]: ../README.md#supported-proto-features--limitations

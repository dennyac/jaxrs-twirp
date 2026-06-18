# dropwizard-twirp-example

A minimal, runnable Dropwizard 5 application that exposes the canonical
[Haberdasher][] Twirp service over HTTP. It's the reference for everything in
the parent [`dropwizard-twirp`](../README.md) repo, but the **primary thing
this module exists to demonstrate is code generation** — how you go from a
`.proto` to a JAX-RS resource you can register with `environment.jersey()` and
a Jersey client your other services can call.

If you've never seen Twirp before, the parent README has a short
["What is Twirp?"](../README.md#what-is-twirp) intro — read that first.

## What's in here

```
src/main/proto/haberdasher.proto                    # the schema (one RPC, two messages)
src/main/java/.../ExampleApplication.java           # bundle registration
src/main/java/.../HaberdasherImpl.java              # business logic (server side)
src/main/resources/example.yml                      # dropwizard config
src/test/java/.../ExampleApplicationIntegrationTest.java  # raw HTTP (curl-equivalent)
src/test/java/.../GeneratedClientIntegrationTest.java     # generated client → live server
```

`haberdasher.proto` is the **only** thing you hand-write that's protocol-shaped.
Everything else either depends on it (your impl, your tests) or is generated
from it.

## Code generation

The example's [`pom.xml`](pom.xml) wires `dropwizard-twirp-protoc` into
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
            <groupId>io.dropwizard.modules</groupId>
            <artifactId>dropwizard-twirp-protoc</artifactId>
            <version>${project.version}</version>
            <mainClass>io.dropwizard.twirp.protoc.Main</mainClass>
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

### Asymmetric setups: client-only or server-only

Sometimes you want to split client and server across modules — the canonical
pattern is publishing a thin client jar that your other apps depend on, while
the server-only deploy lives in a separate module:

```
my-app-api/      # protos + 'server=false' → published as a thin client jar
my-app-server/   # depends on my-app-api, generates 'client=false' → deployed
```

Pass either knob via `<pluginParameter>` on the `<protocPlugin>` element:

```xml
<!-- in my-app-api/pom.xml: only emit the interface + JAX-RS client -->
<protocPlugin>
  <id>twirp_java</id>
  ...
  <pluginParameter>server=false</pluginParameter>
</protocPlugin>

<!-- in my-app-server/pom.xml: only emit the JAX-RS resource (interface comes from -api) -->
<protocPlugin>
  <id>twirp_java</id>
  ...
  <pluginParameter>client=false</pluginParameter>
</protocPlugin>
```

(Setting both to `false` is rejected — the only thing left to emit is the
service interface, which is rarely what you want and is more honestly
expressed as "I want protoc-built java messages, not Twirp".)

### Not using Maven?

`dropwizard-twirp-protoc` is a regular `protoc` plugin — the Maven wiring is
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
$ mvn exec:java -Dexec.mainClass=io.dropwizard.twirp.example.ExampleApplication \
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
[`ExampleApplicationIntegrationTest`](src/test/java/io/dropwizard/twirp/example/ExampleApplicationIntegrationTest.java)
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
[`ExampleApplicationIntegrationTest`](src/test/java/io/dropwizard/twirp/example/ExampleApplicationIntegrationTest.java).
Leave `style` out (or set it to `HAT_STYLE_UNSPECIFIED`) to list the whole shop.

See the top-level [Supported proto features & limitations][features] table for
the complete matrix of what generation handles, what needs configuration
(e.g. `google.protobuf.Any` over JSON), and what's out of scope (streaming).

## Tests

```bash
$ mvn -pl dropwizard-twirp-example test
```

Two suites:

| Suite                                | What it covers                                                                                                          |
| ------------------------------------ | ----------------------------------------------------------------------------------------------------------------------- |
| `ExampleApplicationIntegrationTest`  | Raw HTTP roundtrips — protobuf and JSON, success and error envelopes, both formats interleaved on one resource.         |
| `GeneratedClientIntegrationTest`     | The generated `HaberdasherClient` against the live server, including JSON-mode construction and error-envelope decoding. **Read this for the canonical client-side wiring pattern** (`dropwizard-client`'s `JerseyClientBuilder` + the generated stub). |

[Haberdasher]: https://github.com/twitchtv/twirp/blob/main/example/service.proto
[protobuf-maven-plugin]: https://www.xolstice.org/protobuf-maven-plugin/
[gen-client-test]: src/test/java/io/dropwizard/twirp/example/GeneratedClientIntegrationTest.java
[dw-client]: https://www.dropwizard.io/en/stable/manual/client.html
[raw-protoc]: ../README.md#code-generation-without-maven-raw-protoc-plugin
[features]: ../README.md#supported-proto-features--limitations

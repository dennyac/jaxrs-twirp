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
src/main/java/.../ExampleApplication.java           # bundle + command registration
src/main/java/.../HaberdasherImpl.java              # business logic (server side)
src/main/resources/example.yml                      # dropwizard config
src/test/java/.../ExampleApplicationIntegrationTest.java  # raw HTTP (curl-equivalent)
src/test/java/.../GeneratedClientIntegrationTest.java     # generated client → live server
```

`haberdasher.proto` is the **only** thing you hand-write that's protocol-shaped.
Everything else either depends on it (your impl, your tests) or is generated
from it.

## Code generation — two paths

You have two ways to turn `haberdasher.proto` into Java. Both call the same
plugin under the hood; pick whichever fits the workflow.

### Path A — Maven build-time plugin (this module's default)

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

Use this when the proto + generated code + service impl all live in the same
Maven module. It's the simplest setup: nothing to install, no extra build
step, IDE indexing works out of the box.

### Path B — `twirp-generate` Dropwizard `Command`

`dropwizard-twirp-protoc` also ships [`TwirpGenerateCommand`][gen-command], a
Dropwizard [`Command`][dw-command] you can register on any Dropwizard
`Application`:

```java
@Override
public void initialize(Bootstrap<ExampleConfiguration> bootstrap) {
    bootstrap.addBundle(new TwirpBundle<>());
    bootstrap.addCommand(new TwirpGenerateCommand());   // ← here
}
```

…and then the fat jar gains a `twirp-generate` subcommand. This is
the right tool for **scripts, CI jobs, and splitting client vs server into
separate modules** where the build-time plugin doesn't fit.

The command shells out to `protoc` for descriptor production, then runs the
plugin in-process so there's nothing to put on `$PATH`. Three useful
invocations:

```bash
# Build the fat jar once.
$ mvn -pl dropwizard-twirp-example -am install -DskipTests
$ cd dropwizard-twirp-example
```

**1. Default — emit everything (interface + resource + client):**

```bash
$ java -jar target/dropwizard-twirp-example-*.jar twirp-generate \
       -I src/main/proto \
       --proto haberdasher.proto \
       -o /tmp/twirp-full
Wrote 3 file(s) to /tmp/twirp-full
$ ls /tmp/twirp-full/io/dropwizard/twirp/example/haberdasher/
Haberdasher.java          # the service interface (contract)
HaberdasherResource.java  # JAX-RS resource (server)
HaberdasherClient.java    # JAX-RS client implementing Haberdasher (client)
```

**2. `--no-client` — server-only deploy:**

```bash
# Useful inside a service module that exposes the RPC but never calls
# itself. Keeps WebTarget / jakarta.ws.rs.client off the runtime classpath.
$ java -jar target/dropwizard-twirp-example-*.jar twirp-generate \
       -I src/main/proto --proto haberdasher.proto \
       -o /tmp/twirp-server \
       --no-client
Wrote 2 file(s) to /tmp/twirp-server
$ ls /tmp/twirp-server/io/dropwizard/twirp/example/haberdasher/
Haberdasher.java
HaberdasherResource.java
```

**3. `--no-server` — client-only module:**

```bash
# Useful when you're building a shared client jar (e.g. a "haberdasher-client"
# library) consumed by other apps. They don't want a JAX-RS resource on their
# classpath; they just want to call MakeHat.
$ java -jar target/dropwizard-twirp-example-*.jar twirp-generate \
       -I src/main/proto --proto haberdasher.proto \
       -o /tmp/twirp-client \
       --no-server
Wrote 2 file(s) to /tmp/twirp-client
$ ls /tmp/twirp-client/io/dropwizard/twirp/example/haberdasher/
Haberdasher.java
HaberdasherClient.java
```

(`--no-client --no-server` together is rejected — that combination would
emit only the interface, which is rarely what you want and is more honestly
expressed as "I want protoc-built java messages, not Twirp".)

The same toggles exist for Path A via the plugin parameter (see the
[top-level README plugin options table](../README.md#plugin-options)).
A common asymmetric setup looks like:

```
my-app-api/      # protos + 'client=true,server=false' → published as a thin client jar
my-app-server/   # depends on my-app-api, generates 'client=false,server=true' → deployed
```

### What each generated file does

The example uses the **default** (everything emitted). Here's what you get:

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
[gen-command]: ../dropwizard-twirp-protoc/src/main/java/io/dropwizard/twirp/protoc/TwirpGenerateCommand.java
[gen-client-test]: src/test/java/io/dropwizard/twirp/example/GeneratedClientIntegrationTest.java
[dw-command]: https://www.dropwizard.io/en/stable/manual/core.html#commands
[dw-client]: https://www.dropwizard.io/en/stable/manual/client.html

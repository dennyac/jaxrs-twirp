# dropwizard-twirp

Serve [Twirp](https://github.com/twitchtv/twirp) RPC endpoints from a
[Dropwizard 5](https://www.dropwizard.io/) application using its existing
Jetty/Jersey stack — no separate gRPC server, no HTTP/2, no extra port.

```text
client                                 dropwizard
┌──────────┐    POST /twirp/...     ┌─────────────────────────────┐
│ protobuf │ ─────────────────────► │ Jetty ► Jersey ► Resource   │
│  or JSON │ ◄───────────────────── │            │                │
└──────────┘    body + headers      │  TwirpBundle providers ◄────│── you wire this
                                    │            │                │
                                    │  Haberdasher (interface) ◄──│── you implement this
                                    └─────────────────────────────┘
```

## What is Twirp?

[Twirp](https://github.com/twitchtv/twirp) is a small RPC framework from
Twitch. You describe your service in a `.proto` file just like gRPC, and a
codegen tool produces the client and server stubs. The wire format, however,
is deliberately boring:

- One HTTP/1.1 `POST` per RPC call. No HTTP/2, no streaming, no bidirectional
  channels — just request/response.
- Path is `/<prefix>/<proto-package>.<ServiceName>/<RpcName>` (the prefix is
  `/twirp` by default).
- Body is either `application/protobuf` (binary) or `application/json`. The
  server speaks whichever the client sends.
- Errors come back as a small JSON envelope (`{"code": "...", "msg": "...",
  "meta": {...}}`) with a real HTTP status code, *always* JSON regardless of
  the request content type.

That's the whole spec. Because every Twirp call is a regular HTTP POST with a
regular body, it slots cleanly into any HTTP stack — including Dropwizard's
Jetty+Jersey one. You can hit a Twirp endpoint with `curl`, browse it with a
JSON proxy, or wrap it in a CDN, and it just works.

Comparison cheat sheet:

| You're used to … | Twirp gives you …                                                       |
| ---------------- | ----------------------------------------------------------------------- |
| REST + Jackson   | The same wire shape, but with codegen and a strict schema (no JSON-by-hand). |
| gRPC             | Schema-first RPC over plain HTTP/1.1 — no HTTP/2, no separate server, no streaming. |

If you need streaming or bidirectional RPC, Twirp isn't for you (use gRPC). If
you want strongly-typed RPC that still feels like an HTTP endpoint, it's
hard to beat.

## Why not gRPC?

gRPC on the JVM runs its own HTTP/2 server (Netty by default). Inside a
Dropwizard app that means spinning up a **second** server next to Jetty, with a
separate port, separate metrics/healthcheck/admin story, separate filters, and
a duplicate of every cross-cutting concern.

Twirp ditches the HTTP/2 framing and ships requests over plain HTTP/1.1 POST
with `application/protobuf` or `application/json` bodies. That maps cleanly
onto JAX-RS `@POST` resources with custom `MessageBodyReader` /
`MessageBodyWriter` providers — so you get RPC services that ride on top of the
same Jetty connector, with the same logging, the same metrics, the same admin
endpoints, and the same filters as your REST endpoints.

You lose: streaming RPCs (Twirp doesn't support them) and HTTP/2 multiplexing.
You gain: one server, one port, one operational surface.

## Modules

| Module                       | What it does                                                                                                                |
| ---------------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| `dropwizard-twirp`           | Runtime library: `TwirpBundle`, protobuf + JSON body providers, exception mappers, `TwirpException`, `ErrorCode`, `TwirpClients`. |
| `dropwizard-twirp-protoc`    | Standalone `protoc` plugin (shaded fat-jar) that emits a Java service interface, a JAX-RS resource, and a Jersey client per service. Also ships `TwirpGenerateCommand`. |
| `dropwizard-twirp-example`   | End-to-end example: a Dropwizard app exposing the canonical Haberdasher Twirp service over both wire formats. See [its README](dropwizard-twirp-example/README.md) for runnable server + client demos. |

## Quickstart

### 1. Add the runtime dependency

```xml
<dependency>
    <groupId>io.dropwizard.modules</groupId>
    <artifactId>dropwizard-twirp</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### 2. Write a `.proto`

`src/main/proto/haberdasher.proto`:

```proto
syntax = "proto3";

package twitch.twirp.example.haberdasher;
option java_package = "com.example.haberdasher";
option java_multiple_files = true;

service Haberdasher {
    rpc MakeHat (Size) returns (Hat);
}

message Size { int32 inches = 1; }
message Hat  {
    int32  inches     = 1;
    string color      = 2;
    string style_name = 3;
}
```

### 3. Wire up code generation

Add the [xolstice protobuf-maven-plugin](https://www.xolstice.org/protobuf-maven-plugin/)
and tell it about our plugin:

```xml
<build>
    <extensions>
        <extension>
            <groupId>kr.motd.maven</groupId>
            <artifactId>os-maven-plugin</artifactId>
            <version>1.7.1</version>
        </extension>
    </extensions>
    <plugins>
        <plugin>
            <groupId>org.xolstice.maven.plugins</groupId>
            <artifactId>protobuf-maven-plugin</artifactId>
            <version>0.6.1</version>
            <extensions>true</extensions>
            <configuration>
                <protocArtifact>com.google.protobuf:protoc:4.32.1:exe:${os.detected.classifier}</protocArtifact>
            </configuration>
            <executions>
                <execution>
                    <goals>
                        <goal>compile</goal>
                    </goals>
                    <configuration>
                        <protocPlugins>
                            <protocPlugin>
                                <id>twirp_java</id>
                                <groupId>io.dropwizard.modules</groupId>
                                <artifactId>dropwizard-twirp-protoc</artifactId>
                                <version>0.1.0-SNAPSHOT</version>
                                <mainClass>io.dropwizard.twirp.protoc.Main</mainClass>
                            </protocPlugin>
                        </protocPlugins>
                    </configuration>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

A `mvn compile` will emit (under `target/generated-sources/protobuf/java`):

- protoc's normal Java message classes (`Hat`, `Size`, …)
- `Haberdasher.java` — the service interface for you to implement
- `HaberdasherResource.java` — the JAX-RS resource that wraps it
- `HaberdasherClient.java` — a Jersey client stub that implements `Haberdasher`
  (skip with the `client=false` plugin option if you don't want it)

### 4. Implement the service and wire it into Dropwizard

```java
public class HaberdasherImpl implements Haberdasher {
    @Override
    public Hat makeHat(Size request) throws TwirpException {
        if (request.getInches() <= 0) {
            throw TwirpException.builder(ErrorCode.INVALID_ARGUMENT)
                    .message("inches must be > 0")
                    .meta("argument", "inches")
                    .build();
        }
        return Hat.newBuilder()
                .setInches(request.getInches())
                .setColor("red")
                .setStyleName(request.getInches() >= 10 ? "fedora" : "bowler")
                .build();
    }
}

public class MyApplication extends Application<MyConfiguration> {
    @Override
    public void initialize(Bootstrap<MyConfiguration> bootstrap) {
        bootstrap.addBundle(new TwirpBundle<>());   // <-- registers providers + mappers
    }

    @Override
    public void run(MyConfiguration config, Environment env) {
        env.jersey().register(new HaberdasherResource(new HaberdasherImpl()));
    }
}
```

That's it. Start the app and POST to it:

```bash
# protobuf
$ curl -X POST -H 'Content-Type: application/protobuf' \
      --data-binary @size.bin \
      http://localhost:8080/twirp/twitch.twirp.example.haberdasher.Haberdasher/MakeHat

# JSON
$ curl -X POST -H 'Content-Type: application/json' \
      -d '{"inches": 12}' \
      http://localhost:8080/twirp/twitch.twirp.example.haberdasher.Haberdasher/MakeHat
{"inches":12,"color":"red","style_name":"fedora"}
```

## Runtime API

### `TwirpBundle`

A `ConfiguredBundle<C extends Configuration>` that registers everything needed
to speak Twirp:

- `ProtobufMessageBodyReader` / `Writer` for `application/protobuf`
- `ProtobufJsonMessageBodyReader` / `Writer` for `application/json`
- `TwirpExceptionMapper` — turns `TwirpException` into the wire-format JSON
- `InvalidProtocolBufferExceptionMapper` — turns bad-wire-bytes into a
  Twirp `malformed` 400 response

Customize the JSON printer / parser via the builder when you need different
field-presence semantics, integer formatting, etc.:

```java
bootstrap.addBundle(TwirpBundle.builder()
        .jsonPrinter(JsonFormat.printer().omittingInsignificantWhitespace())
        .jsonParser(JsonFormat.parser().ignoringUnknownFields())
        .<MyConfig>build());
```

The defaults match Twirp's Go reference server:

- `preservingProtoFieldNames()` so `style_name` stays `style_name`, not
  `styleName`, in JSON output
- `includingDefaultValueFields()` so consumers don't have to distinguish
  "absent" from "default" for proto3 scalars
- `omittingInsignificantWhitespace()` for compact responses

### `TwirpException`

Throw this from your service implementation to produce a structured Twirp
error response (HTTP status derived from the code per the Twirp spec):

```java
throw TwirpException.notFound("hat does not exist");

throw TwirpException.invalidArgument("inches", "must be positive");

throw TwirpException.builder(ErrorCode.UNAVAILABLE)
        .message("warehouse is on a coffee break")
        .meta("retry_after", "15s")
        .build();
```

Anything else thrown from a generated resource method is automatically wrapped
as `ErrorCode.INTERNAL` by `TwirpInvocations.invoke` so leaky 500s don't reach
the client. `InterruptedException` becomes `ErrorCode.UNAVAILABLE` and the
thread's interrupt status is re-asserted before the response is built.

### Wire-format contract

The generated resource binds:

- `@Path("/twirp/<proto-package>.<ServiceName>")` at the class level
- `@POST @Path("/<RpcName>")` per RPC (RPC name keeps its original
  `UpperCamelCase`, matching the Twirp v7 spec)
- `@Consumes({"application/protobuf", "application/json"})` and
  `@Produces(...)` for both wire formats

If a request supplies `Content-Type: application/protobuf` the response is also
protobuf; same for JSON. Twirp error responses are *always* JSON regardless of
the inbound content type — that's the spec, so clients can decode errors
without protobuf descriptors.

## Calling a Twirp service from Java

`dropwizard-twirp-protoc` also emits a `<Service>Client` per service, e.g.
`HaberdasherClient`, that implements the same service interface. Give it a
Jersey `WebTarget` rooted at the remote app:

```java
Client client = new JerseyClientBuilder(env).build("haberdasher");
Haberdasher remote = new HaberdasherClient(client.target("https://hats.example.com"));

try {
    Hat hat = remote.makeHat(Size.newBuilder().setInches(12).build());
    log.info("got {}", hat);
} catch (TwirpException ex) {
    // Server-side TwirpExceptions are decoded back into the same exception
    // type with the original code, message, and meta map intact. Network
    // failures surface as ErrorCode.UNAVAILABLE.
    log.warn("haberdasher failed: {} ({})", ex.getMessage(), ex.getErrorCode());
}
```

A few useful properties:

- The default wire format is `application/protobuf`. Pass
  `TwirpMediaTypes.APPLICATION_JSON` as the second constructor argument to
  switch to JSON.
- The constructor registers the standard Twirp body providers on the supplied
  `WebTarget` for you — no extra Jersey wiring is required.
- Twirp error responses (any non-2xx with a JSON body) are decoded into
  `TwirpException` with the original `ErrorCode`, message, and meta map.
  Unknown wire codes fall back to `ErrorCode.UNKNOWN` with a snippet of the
  body in `meta["body"]`.
- Transport failures (connection refused, DNS, …) surface as
  `ErrorCode.UNAVAILABLE`; bodies that can't be parsed as a Twirp error
  surface as `ErrorCode.MALFORMED` rather than leaking
  `WebApplicationException`.

Any Jersey/JAX-RS `Client` works — Dropwizard's `JerseyClientBuilder` is the
common choice but it's not required.

## Generating from a packaged jar (`twirp-generate` command)

For workflows that don't want a Maven build step — or for ops folks who only
have the jar — `dropwizard-twirp-protoc` ships a Dropwizard
[`Command`](https://www.dropwizard.io/en/stable/manual/core.html#commands)
called `twirp-generate`. Wire it in alongside `TwirpBundle`:

```java
@Override
public void initialize(Bootstrap<MyConfiguration> bootstrap) {
    bootstrap.addBundle(new TwirpBundle<>());
    bootstrap.addCommand(new TwirpGenerateCommand());
}
```

Then any `java -jar myapp.jar twirp-generate …` invocation regenerates the
stubs:

```bash
$ java -jar myapp.jar twirp-generate \
      -I src/main/proto \
      --proto haberdasher.proto \
      --output-dir target/generated-sources/twirp
Wrote 3 file(s) to /abs/path/to/target/generated-sources/twirp
```

| Flag                       | Default  | Effect                                                                   |
| -------------------------- | -------- | ------------------------------------------------------------------------ |
| `--proto FILE`             | required | A `.proto` to generate from. Repeat for multiple files.                  |
| `--proto-path DIR` / `-I`  | `.`      | Search path for `import`s. Repeat for multiple roots.                    |
| `--output-dir DIR` / `-o`  | required | Where to write generated Java sources. Created if missing.               |
| `--prefix PATH`            | `/twirp` | URL prefix on every generated `@Path`.                                   |
| `--no-client`              | off      | Skip generating the Jersey client stub.                                  |
| `--protoc PATH`            | `protoc` | Path to the `protoc` binary; `protoc` on `$PATH` by default.             |

The command shells out to `protoc --descriptor_set_out=…` for parsing, then
runs the same in-process plugin the Maven build uses. It's the same emitter,
the same code — just driven via the CLI instead of xolstice.

> **Note:** `TwirpGenerateCommand` lives in `dropwizard-twirp-protoc`. That
> module declares its `dropwizard-core` dependency as `provided` so the shaded
> protoc fat jar stays small (and so xolstice's plugin invocation doesn't drag
> in Dropwizard at build time). Your application's existing `dropwizard-core`
> dependency satisfies the symbol at runtime.

## Code generation, alternative invocations

The `dropwizard-twirp-protoc` jar is also a self-contained `protoc` plugin you
can drive directly from `protoc` itself (no Dropwizard application required):

```bash
$ cat > protoc-gen-twirp_java <<'EOF'
#!/usr/bin/env sh
exec java -jar /opt/dropwizard-twirp-protoc-0.1.0-SNAPSHOT.jar
EOF
$ chmod +x protoc-gen-twirp_java
$ PATH=$PWD:$PATH protoc --twirp_java_out=. haberdasher.proto
```

Plugin options (`--twirp_java_out=<key>=<value>,<key>=<value>:OUT`):

| Option   | Default  | Effect                                                                          |
| -------- | -------- | ------------------------------------------------------------------------------- |
| `prefix` | `/twirp` | URL path prefix prepended to every `@Path` annotation.                          |
| `client` | `true`   | Whether to emit a Jersey `<Service>Client`. Set to `false` for server-only deploys. |

The Maven equivalent is the `<pluginParameter>` element on `<protocPlugin>`:

```xml
<protocPlugin>
    <id>twirp_java</id>
    ...
    <pluginParameter>prefix=/rpc,client=false</pluginParameter>
</protocPlugin>
```

## Status

This is **0.1.0-SNAPSHOT**. The runtime, codegen, client, and command are all
tested end-to-end (89 tests across the reactor) but the API is not yet frozen.

Roadmap ideas (not yet implemented):

- A `NotFoundExceptionMapper` so unknown Twirp routes return a JSON
  `bad_route` error instead of Jersey's HTML 404.
- Optional client interceptors for adding auth headers / tracing context
  without subclassing the generated client.
- Server-side request validation hooks (currently the generated resource
  passes the protobuf straight to the impl).

## Building from source

```bash
$ mvn clean install
```

Requires JDK 17+ and Maven 3.9+. The xolstice plugin pulls protoc and the
relevant libprotoc native binary from Maven Central, so you don't need a
system protoc.

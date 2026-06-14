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

| Module                       | What it does                                                                                                       |
| ---------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| `dropwizard-twirp`           | Runtime library: `TwirpBundle`, protobuf + JSON body providers, exception mappers, `TwirpException`, `ErrorCode`.   |
| `dropwizard-twirp-protoc`    | Standalone `protoc` plugin (shaded fat-jar) that emits a Java service interface plus a JAX-RS resource per service. |
| `dropwizard-twirp-example`   | End-to-end example: a Dropwizard app exposing the canonical Haberdasher Twirp service over both wire formats.      |

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

## Code generation, alternative invocations

The `dropwizard-twirp-protoc` jar is a self-contained `protoc` plugin. You can
also run it without Maven:

```bash
# unzip the shaded jar somewhere
$ cat > protoc-gen-twirp_java <<'EOF'
#!/usr/bin/env sh
exec java -jar /opt/dropwizard-twirp-protoc-0.1.0-SNAPSHOT.jar
EOF
$ chmod +x protoc-gen-twirp_java
$ PATH=$PWD:$PATH protoc --twirp_java_out=. haberdasher.proto
```

Plugin options:

| Option   | Default  | Effect                                                 |
| -------- | -------- | ------------------------------------------------------ |
| `prefix` | `/twirp` | URL path prefix prepended to every `@Path` annotation. |

Pass via `--twirp_java_out=prefix=/rpc:.` on the protoc CLI. With the Maven
plugin, the equivalent is the `<pluginParameter>` element on `<protocPlugin>`:

```xml
<protocPlugin>
    <id>twirp_java</id>
    ...
    <pluginParameter>prefix=/rpc</pluginParameter>
</protocPlugin>
```

## Status

This is **0.1.0-SNAPSHOT**. The runtime, codegen, and wire-format contract are
all tested end-to-end (53 tests across the reactor) but the API is not yet
frozen.

Roadmap ideas (not yet implemented):

- A Dropwizard `Command` so you can run `java -jar myapp.jar twirp-generate
  …protos…` from the CLI (the current path is the Maven plugin).
- A `NotFoundExceptionMapper` so unknown Twirp routes return a JSON
  `bad_route` error instead of Jersey's HTML 404.
- A matching Java client generator so RPC clients are codegen'd too.

## Building from source

```bash
$ mvn clean install
```

Requires JDK 17+ and Maven 3.9+. The xolstice plugin pulls protoc and the
relevant libprotoc native binary from Maven Central, so you don't need a
system protoc.

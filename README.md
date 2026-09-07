# jaxrs-twirp

Generate and run [Twirp](https://github.com/twitchtv/twirp) clients and servers
on Jakarta REST (JAX-RS) 3.1. The portable core works with JAX-RS
implementations such as Jersey and RESTEasy; the `dropwizard-twirp` module adds
first-class [Dropwizard 5](https://www.dropwizard.io/) lifecycle and client
integration.

```text
client                              JAX-RS application
┌──────────┐    POST /twirp/...     ┌─────────────────────────────┐
│ protobuf │ ─────────────────────► │ HTTP ► JAX-RS ► Resource    │
│  or JSON │ ◄───────────────────── │            │                │
└──────────┘    body + headers      │ TwirpServerFeature providers│
                                    │            │                │
                                    │  Haberdasher (interface) ◄──│── you implement this
                                    └─────────────────────────────┘
```

## What is Twirp?

[Twirp](https://github.com/twitchtv/twirp) is a small RPC framework from
Twitch. You describe your service in a `.proto` file, and a
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
regular body, it slots cleanly into any JAX-RS HTTP stack. You can hit a Twirp
endpoint with `curl`, browse it with a JSON proxy, or wrap it in a CDN.

If you're coming from **REST + Jackson**, Twirp keeps the same HTTP/1.1
request/response shape but adds codegen and a strict `.proto` schema — so you
stop hand-writing JSON DTOs and URL routing, and callers get a typed client stub
for free.

If you need streaming or bidirectional RPC, Twirp isn't for you. If
you want strongly-typed RPC that still feels like an HTTP endpoint, it's
hard to beat.

## How it fits JAX-RS and Dropwizard

Twirp ships requests over plain HTTP/1.1 POST with `application/protobuf` or
`application/json` bodies. That maps cleanly onto JAX-RS `@POST` resources with
custom `MessageBodyReader` / `MessageBodyWriter` providers. The generated
resources and clients depend only on the JAX-RS API and `jaxrs-twirp-core`.

In Dropwizard, `dropwizard-twirp` registers those providers on the existing
Jersey environment and offers a managed client builder, so RPC services use the
same Jetty connector, logging, metrics, filters, and lifecycle as REST endpoints.

You lose streaming RPCs (Twirp has none by design). You gain one server, one
port, and one operational surface.

## Modules

| Module                       | What it does                                                                                                                |
| ---------------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| `jaxrs-twirp-core`           | Framework-agnostic JAX-RS runtime: protobuf + JSON body providers, exception mappers, route-scoped `bad_route` handling, `TwirpServerFeature`, `TwirpAuthFeature`, `TwirpContext`, `TwirpException`, `ErrorCode`, `TwirpClients`, and `TwirpJson`. Depends only on the JAX-RS API (plus protobuf, Jackson, SLF4J) — **no Dropwizard**. |
| `dropwizard-twirp`           | Dropwizard veneer over the core: `TwirpBundle` and the managed `TwirpClientBuilder`. This is the dependency a Dropwizard app adds; it pulls in the core transitively. |
| `jaxrs-twirp-protoc`         | Standalone `protoc` plugin (shaded fat-jar) that emits a Java service interface, a JAX-RS resource, and a portable JAX-RS client per service. The generated code depends only on the core. |
| `dropwizard-twirp-example`   | End-to-end example: a Dropwizard app exposing the canonical Haberdasher Twirp service over both wire formats. See [its README](dropwizard-twirp-example/README.md) for runnable server + client demos. |

The runtime is split so the protocol/codec layer stays a plain JAX-RS library:
generated server and client code, the codecs, and the error model all live in
`jaxrs-twirp-core` and would run on any JAX-RS 3.1 container. `dropwizard-twirp`
adds only the Dropwizard-specific glue. Apps depend on `dropwizard-twirp` and get
the core transitively — there's nothing extra to wire up.

## Dropwizard quickstart

### 1. Add the runtime dependency

> **Not published yet.** These artifacts aren't on Maven Central, so this
> coordinate won't resolve until you build them locally — clone this repo and
> run `mvn clean install` first (see [Building from source](#building-from-source)).

```xml
<dependency>
    <groupId>com.dennyac.twirp</groupId>
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
                                <groupId>com.dennyac.twirp</groupId>
                                <artifactId>jaxrs-twirp-protoc</artifactId>
                                <version>0.1.0-SNAPSHOT</version>
                                <mainClass>com.dennyac.twirp.protoc.Main</mainClass>
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

That's the **server** half end to end: proto → generated resource → running
endpoint. For the **client** half — calling this service from another Java app
with the generated stub — jump to
[Calling a Twirp service from Java](#calling-a-twirp-service-from-java). For a
complete runnable project that exercises both sides (plus `curl` recipes for
each wire format), see the
[`dropwizard-twirp-example`](dropwizard-twirp-example/README.md) module.

## Runtime API

### `TwirpBundle`

A `ConfiguredBundle<C extends Configuration>` that registers everything needed
to speak Twirp:

- `ProtobufMessageBodyReader` / `Writer` for `application/protobuf`
- `ProtobufJsonMessageBodyReader` / `Writer` for `application/json`
- `TwirpExceptionMapper` — turns `TwirpException` into the wire-format JSON
- `InvalidProtocolBufferExceptionMapper` — turns bad-wire-bytes into a
  Twirp `malformed` 400 response
- a prefix-aware response filter that turns Twirp routing and content-type
  failures into `bad_route` 404 responses without changing ordinary REST errors

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

If code generation uses a non-default URL prefix, configure the same prefix on
the bundle so routing failures are recognized as Twirp requests:

```java
bootstrap.addBundle(TwirpBundle.builder()
        .pathPrefix("/rpc")
        .<MyConfig>build());
```

If any of your messages embed a `google.protobuf.Any`, JSON serialization needs
a `TypeRegistry` that knows the packed types (binary protobuf carries the type
URL inline and needs no registry). Supply one via the builder:

```java
bootstrap.addBundle(TwirpBundle.builder()
        .typeRegistry(TypeRegistry.newBuilder()
                .add(MyPackedMessage.getDescriptor())
                .build())
        .<MyConfig>build());
```

See [Supported proto features & limitations](#supported-proto-features--limitations)
for the full matrix of what the codegen and runtime handle.

### Serving Twirp without Dropwizard (plain JAX-RS)

`TwirpBundle` is a thin Dropwizard convenience — it just installs a
`TwirpServerFeature` on the Jersey environment. That feature lives in
`jaxrs-twirp-core` and carries no Dropwizard dependency, so any JAX-RS 3.1
application (Jersey, RESTEasy, …) can serve the same generated resources by
depending on the core directly and registering the feature itself:

```java
// dependency: com.dennyac.twirp:jaxrs-twirp-core
ResourceConfig config = new ResourceConfig();
config.register(new TwirpServerFeature());                 // codecs + Twirp errors under /twirp
config.register(new HaberdasherResource(new MyHaberdasher()));
```

`TwirpServerFeature` registers the four protobuf/JSON body providers, two
exception mappers, and the route-aware `bad_route` response filter — the exact
set `TwirpBundle` installs. Pass a custom `JsonFormat.Printer` / `Parser` to its
two-arg constructor for the same field-presence customization the bundle offers.
For custom codegen prefixes, use `new TwirpServerFeature("/rpc")` or the
printer/parser/prefix constructor.

The generated resource and the default generated client depend only on the core
(`jakarta.ws.rs.*` plus the Twirp runtime helpers), so the protobuf wire format,
the JSON wire format, and the error model all work with no Dropwizard on the
classpath. The only opt-in that reaches back into the Dropwizard veneer is the
`clientBuilder` codegen flag (the managed `TwirpClientBuilder`); leave it off and
the generated client stays pure JAX-RS too — see
[Calling a Twirp service from Java](#calling-a-twirp-service-from-java).

#### Routing errors in mixed REST/Twirp applications

The route filter rewrites JAX-RS 404, 405, and 415 responses only when the
request path is the configured Twirp prefix or a child of it. With the default
configuration, `/twirp/example.Service/Nope` becomes Twirp `bad_route`, while
`/api/missing`, a wrong method on `/api/users`, and an unsupported media type on
that REST resource retain the application's normal 404, 405, and 415 responses.
Legitimate service-level Twirp errors such as `not_found` are also preserved.

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

`jaxrs-twirp-protoc` also emits a `<Service>Client` per service, e.g.
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
  Bodies that aren't a recognizable Twirp envelope fall back to
  `ErrorCode.UNKNOWN`, with the HTTP status and a body snippet folded into the
  exception message.
- Transport failures (connection refused, DNS, …) surface as
  `ErrorCode.UNAVAILABLE`; bodies that can't be parsed as a Twirp error
  surface as `ErrorCode.MALFORMED` rather than leaking
  `WebApplicationException`.

Any JAX-RS `Client` works — Dropwizard's `JerseyClientBuilder` is the common
choice (and the one the example uses) because you get metrics, Apache
HttpClient, configurable timeouts, and lifecycle management for free, but the
generated stub itself doesn't depend on Dropwizard. If you ever want to call
the same service from a non-Dropwizard app, drop the generated jar in and
hand it a vanilla `ClientBuilder.newClient()`.

### The managed client builder (optional)

The raw constructor wants the **root** `WebTarget` (the bare remote origin) — it
appends the Twirp path itself. That's easy to get subtly wrong (passing an
already-pathed target double-prefixes the URL). The runtime ships a fluent
`TwirpClientBuilder` that removes the footgun: hand it a managed `Client` (or an
`Environment` + `JerseyClientConfiguration`) plus a `baseUri`, and it resolves
the root target and content type for you.

```java
// From a Client you already built (e.g. shared across stubs):
Haberdasher remote = TwirpClientBuilder.forService(HaberdasherClient::new)
        .using(client)
        .baseUri("https://hats.example.com")
        .json()                 // or .protobuf() (default)
        .build();

// Or let the builder create a managed JerseyClient from Dropwizard config:
Haberdasher remote = TwirpClientBuilder.forService(HaberdasherClient::new)
        .using(environment, new JerseyClientConfiguration())
        .baseUri("https://hats.example.com")
        .build();
```

`TwirpClientBuilder` lives in the `dropwizard-twirp` runtime and is always
available. The `using(environment, configuration)` overload needs
`io.dropwizard:dropwizard-client`, which the runtime declares as an **optional**
dependency — server-only apps never pull it onto their classpath, and you only
add it yourself when you actually build clients this way.

If you'd rather call `HaberdasherClient.builder(environment, configuration)`
directly — the sugar form — run codegen with `clientBuilder=true` and the
generator emits that static factory on the client (it just delegates to
`TwirpClientBuilder`). It's **off by default** because it bakes a compile-time
reference to `dropwizard-client` into the generated client; leaving it off keeps
the generated stub dependency-light (JAX-RS API only). Either way the raw
`WebTarget` constructors stay as the escape hatch.

```xml
<!-- in the twirp_java <protocPlugin> -->
<args>
    <arg>clientBuilder=true</arg>
</args>
```

## Code generation without Maven (raw `protoc` plugin)

The `jaxrs-twirp-protoc` shaded jar is a self-contained `protoc` plugin,
so any build system that can invoke `protoc` (Gradle, Bazel, Make, plain
shell) can drive it. There's nothing Dropwizard-specific about generation
itself — `protoc` just needs to find an executable named
`protoc-gen-twirp_java` on `PATH`, which is a one-line shell shim around the
jar:

```bash
$ cat > protoc-gen-twirp_java <<'EOF'
#!/usr/bin/env sh
exec java -jar /opt/jaxrs-twirp-protoc-0.1.0-SNAPSHOT.jar
EOF
$ chmod +x protoc-gen-twirp_java
$ PATH=$PWD:$PATH protoc \
        --twirp_java_out=. \
        -I src/main/proto \
        haberdasher.proto
```

Plugin options are passed as `--twirp_java_out=<key>=<value>,<key>=<value>:OUT`:

<a id="plugin-options"></a>

| Option   | Default  | Effect                                                                                       |
| -------- | -------- | -------------------------------------------------------------------------------------------- |
| `prefix` | `/twirp` | URL path prefix prepended to every `@Path` annotation.                                       |
| `client` | `true`   | Emit a JAX-RS `<Service>Client`. Set to `false` for server-only deploys.                     |
| `server` | `true`   | Emit a JAX-RS `<Service>Resource`. Set to `false` for client-only modules (shared client jar consumed by other apps). |
| `clientBuilder` | `false` | Also emit a static `<Service>Client.builder(Environment, JerseyClientConfiguration)` factory. Couples the generated client to `dropwizard-client`; off by default. The runtime `TwirpClientBuilder` gives the same ergonomics without the coupling. |
| `context` | `false` | Append a trailing `TwirpContext` parameter containing inbound headers and the JAX-RS `SecurityContext` to each generated interface, resource, and client method. |

Setting both `client=false` and `server=false` is rejected — the only thing
that would be emitted is the service interface, which is rarely what you want
and is better expressed as "use plain protoc, not Twirp codegen".

The exact same option keys also work from the Maven plugin. Pass them as
`<args>` on the `<protocPlugin>`; the generator merges those arguments with
protoc's option parameter:

```xml
<protocPlugin>
    <id>twirp_java</id>
    ...
    <args>
        <arg>prefix=/rpc,client=false</arg>
    </args>
</protocPlugin>
```

A common asymmetric setup splits client and server into separate modules:

```
my-app-api/      # protos + 'server=false' → published as a thin client jar
my-app-server/   # depends on my-app-api, generates 'client=false' → deployed
```

<a id="request-context"></a>

## Request context and auth

By default a generated method is transport-free:

```java
Hat makeHat(Size request) throws TwirpException;
```

With `context=true`, every generated service, resource, client, and
implementation method gains a trailing `TwirpContext`:

```java
Hat makeHat(Size request, TwirpContext context) throws TwirpException;
```

`TwirpContext` lives in `jaxrs-twirp-core` and exposes an immutable,
case-insensitive header snapshot plus JAX-RS authentication information:

| Method | Value |
| --- | --- |
| `header(name)` / `headerValues(name)` | One or all values of an inbound header |
| `headers()` | The full unmodifiable header map |
| `outboundHeaders()` | Headers explicitly approved for generated-client propagation |
| `principal()` | The authenticated `Principal`, if any |
| `isUserInRole(role)` | A role check delegated to `SecurityContext` |
| `securityContext()` | The underlying JAX-RS context for advanced cases |

On the server, the generated resource injects `HttpHeaders` and
`SecurityContext`, creates the `TwirpContext`, and passes it to the service
implementation. Those inbound headers are never forwarded implicitly. On the
client, `TwirpContext.ofOutboundHeaders(...)` supplies an explicit outbound set:

```java
TwirpContext context = TwirpContext.ofOutboundHeaders(
        Map.of("Authorization", List.of("Bearer " + token)));
WhoAmIResponse me = client.whoAmI(WhoAmIRequest.getDefaultInstance(), context);
```

The factory rejects transport-controlled and hop-by-hop headers such as
`Content-Type`, `Content-Length`, `Host`, `Connection`, and
`Transfer-Encoding`. Authorization, trace, and application metadata remain
available when selected explicitly.

For dropwizard-auth, generated methods do not declare an `@Auth` parameter, so
bind its filter with `TwirpAuthFeature`:

```java
environment.jersey().register(TwirpAuthFeature.forRpcs(
        authFilter,
        HaberdasherResource.class,
        "WhoAmI"));
```

The helper resolves the generated `@Path("/WhoAmI")` at startup and rejects
unknown RPC names rather than silently leaving them unprotected. The auth filter
populates the JAX-RS `SecurityContext`; `principal()` and `isUserInRole(...)`
then expose the result. The [example](dropwizard-twirp-example/README.md#request-context--auth)
contains the complete `OAuthCredentialAuthFilter` setup.

The option is intentionally off by default because it changes the shared Java
interface and clients and servers must use the same setting. Header values are
safe snapshots; the underlying `SecurityContext` remains request-scoped.
`TwirpContext` does not currently model cancellation or deadlines.

## How this compares to Spring Boot / other Java Twirp options

Twirp is Go-first; on the JVM it's a patchwork of community projects, so it's
worth knowing where this one sits.

| Project | Server it targets | Client | Codegen driver | On Maven Central |
| ------- | ----------------- | ------ | -------------- | ---------------- |
| **jaxrs-twirp** (this repo) | Jakarta REST 3.1; first-class Dropwizard adapter | Portable JAX-RS stub that decodes Twirp errors | `protoc` plugin (Maven or raw) | not yet (`0.1.0-SNAPSHOT`) |
| [github/flit][flit] | JAX-RS/Jakarta REST, Spring, Undertow | none | `protoc` plugin | yes |
| [ngyewch/protoc-gen-twirp-java][ngyewch] | Helidon SE | Apache HttpClient | `protoc` plugin (Gradle) | yes |
| Twitch's `protoc-gen-twirp_java` | — | — | experimental, never finished | no |

### "Spring Boot has Twirp support" — sort of

There is no official Spring Boot Twirp starter. Flit generates Spring server
bindings, while this project targets JAX-RS and adds both generated clients and
the Dropwizard-specific lifecycle integration.

If you're a Spring shop with no Dropwizard, this repo won't drop straight in: the
generated resource is `jakarta.ws.rs` (JAX-RS), which runs on Jersey or RESTEasy
but **not** Spring MVC. A Spring team that doesn't specifically need Twirp's
HTTP/1.1-only simplicity won't get much from this module. The case for Twirp
anywhere is the same as the case for this module: plain HTTP/1.1, no second
server, both wire formats on one URL.

[ngyewch]: https://github.com/ngyewch/protoc-gen-twirp-java
[flit]: https://github.com/github/flit

## Supported proto features & limitations

Code generation works at the **service boundary** — the plugin only resolves the
Java class names of each RPC's request/response message and leaves the actual
field serialization to protoc's standard Java output and
`com.google.protobuf.util.JsonFormat`. That means almost all message-level
complexity is handled "for free" by the protobuf runtime, and the short list of
real gaps below is about the *service* layer and JSON edge cases.

The example in `dropwizard-twirp-example` exercises the rich-message path
end-to-end: its `ListInventory` RPC returns an enum, a `repeated` nested message,
and a `map<string, int32>`, asserted over both protobuf and JSON wire formats.

### Handled

| Feature / edge case | Status | Notes |
|---|---|---|
| Enums, nested messages, `repeated`, `map<k,v>`, `oneof` | ✅ | Pure protoc Java codegen; serialized per the proto3 JSON spec. Covered by the example's `ListInventory`. |
| Imports / cross-file message references | ✅ | `TypeMapper` is built from *all* descriptors protoc passes (imports + well-known types), so an RPC can use a message from another `.proto` and still resolve to the right `java_package`. |
| Multiple services in one `.proto` | ✅ | The plugin iterates every service in the file. |
| Proto3 `optional` (field presence) | ✅ | Plugin advertises `FEATURE_PROTO3_OPTIONAL`. |
| `google.protobuf.Empty` and other well-known types as request/response | ✅ | Resolved through the same transitive-descriptor mechanism. |
| `Timestamp`, `Duration`, `Struct`, `Value`, `FieldMask`, wrappers in JSON | ✅ | `JsonFormat` renders these natively, no registry needed. |
| Configurable URL prefix | ✅ | `prefix` generator option (defaults to `/twirp`, per Twirp v7). |
| JSON snake_case names / unknown-field tolerance / default-value emission | ✅ | Go-reference-compatible defaults; override via the `TwirpBundle` builder. |
| RPC names that lowercase to a Java keyword (`Return`, `Import`, …) | ✅ | The generated Java method is suffixed with `_` (e.g. `return_`); the URL path keeps the original proto name, so wire compatibility is unaffected. |
| Unroutable requests (unknown URL, non-`POST`, unsupported content type) | ✅ | Returned as Twirp `bad_route` JSON only beneath configured Twirp prefixes; REST 404/405/415 responses are preserved. |

### Needs configuration

| Feature / edge case | Status | What to do |
|---|---|---|
| `google.protobuf.Any` over **JSON** | ⚠️ | `JsonFormat` cannot resolve a packed `Any` to JSON without a `TypeRegistry`. Register the packed types with `TwirpBundle.builder().typeRegistry(...)` (see above). Binary protobuf needs nothing — the type URL travels inline. |

### Not supported (by design or not yet)

| Feature / edge case | Status | Notes |
|---|---|---|
| Streaming RPCs | ❌ by design | Twirp itself has no streaming — it's a non-goal of the protocol. The generator rejects streaming methods at codegen time with a clear error rather than emitting something that can't work over unary HTTP. |
| Protobuf **editions** (`edition = "2023"`) | ❌ not yet | The plugin does not yet declare `FEATURE_SUPPORTS_EDITIONS`, so protoc 25+ refuses to run it on editions files. Stay on `syntax = "proto3"` for now. (Editions appear to need no Twirp-specific codegen changes, so this is a declaration/testing gap, not a design limit.) |
| `[json_name = "..."]` field option | ⚠️ intentionally ignored | With `preservingProtoFieldNames()` the raw proto field name wins, matching Go Twirp's `UseProtoNames: true`. If you need `json_name` honored, supply a custom `jsonPrinter`/`jsonParser` without name preservation — at the cost of diverging from the Go server. |
| proto2 `required` field missing on decode | ⚠️ maps to `internal` | A missing `required` field throws after decode and surfaces as a Twirp `internal` (HTTP 500) rather than `malformed` (400). proto3 has no `required`, so this only affects proto2 schemas. |

This matrix was assembled by auditing the generated code against the Twirp v7
spec and the known issue trackers of the Go reference generator and the other
JVM Twirp generators (`fajran/protoc-gen-twirp_java_jaxrs`,
`ngyewch/protoc-gen-twirp-java`). The `Any`-over-JSON behavior is verified by
`AnyJsonCodecTest`, the keyword-mangling by `JavaNamingTest`, and the
route-scoped `bad_route` handling by `ExampleApplicationIntegrationTest`.

## Status

This is **0.1.0-SNAPSHOT**. The runtime, codegen, and generated client are all
tested end-to-end (142 tests across the reactor) but the API is not yet frozen.

Roadmap ideas (not yet implemented):

- Declare `FEATURE_SUPPORTS_EDITIONS` so protoc can run the plugin on
  `edition = "2023"` files (see the limitations table above).
- Map proto2 `required`-field validation failures to a Twirp `malformed` (400)
  instead of the current `internal` (500).
- Cancellation/deadline semantics for `TwirpContext`.
- Server-side request validation hooks (currently the generated resource
  passes the protobuf straight to the impl).

## Building from source

```bash
$ mvn clean install
```

Requires JDK 17+ and Maven 3.9+. The xolstice plugin pulls protoc and the
relevant libprotoc native binary from Maven Central, so you don't need a
system protoc.

## License

Licensed under the [Apache License 2.0](LICENSE); see [`NOTICE`](NOTICE) for
attribution. This is an independent implementation and is not affiliated with or
endorsed by Twitch.

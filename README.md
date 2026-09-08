# jaxrs-twirp

JAX-RS client and server code generation for [Twirp](https://github.com/twitchtv/twirp) RPC.

Describe a service in a `.proto`, run `protoc`, and get a Java service
interface, a Jakarta REST (JAX-RS) 3.1 resource that serves it, and a client
stub that calls it. The runtime depends only on the JAX-RS API, so it runs on
any JAX-RS 3.1 implementation — Jersey, RESTEasy, and so on.

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
Twitch. You describe your service in a `.proto` file, and a codegen tool
produces the client and server stubs. The wire format is deliberately boring:

- One HTTP/1.1 `POST` per RPC call. No HTTP/2, no streaming, no bidirectional
  channels — just request/response.
- Path is `/<prefix>/<proto-package>.<ServiceName>/<RpcName>` (the prefix is
  `/twirp` by default).
- Body is either `application/protobuf` (binary) or `application/json`. The
  server speaks whichever the client sends.
- Errors come back as a small JSON envelope (`{"code": "...", "msg": "...",
  "meta": {...}}`) with a real HTTP status code, *always* JSON regardless of
  the request content type.

That's the whole spec. Because every call is a regular HTTP POST with a regular
body, you can hit an endpoint with `curl`, browse it with a JSON proxy, or wrap
it in a CDN. Coming from REST + Jackson you keep the same request/response
shape but stop hand-writing JSON DTOs and URL routing, and callers get a typed
client stub for free. If you need streaming or bidirectional RPC, Twirp isn't
for you.

## How it fits JAX-RS

A Twirp call maps onto a JAX-RS `@POST` resource with custom
`MessageBodyReader` / `MessageBodyWriter` providers. Generated resources and
clients depend only on `jakarta.ws.rs.*` and `jaxrs-twirp-core`, so RPC
endpoints run on the same server, port, filter chain, and logging as your REST
endpoints. You lose streaming RPCs (Twirp has none by design); you gain one
server, one port, and one set of operational tooling. Framework adapters stay
thin: `dropwizard-twirp` registers the same core `TwirpServerFeature` on
Dropwizard's Jersey environment and adds a managed client builder.

## Modules

| Module                       | What it does                                                                                                                |
| ---------------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| `jaxrs-twirp-core`           | Framework-agnostic JAX-RS runtime: protobuf + JSON body providers, exception mappers, route-scoped `bad_route` handling, `TwirpServerFeature`, `TwirpAuthFeature`, `TwirpContext`, `TwirpException`, `ErrorCode`, `TwirpClients`, and `TwirpJson`. Depends only on the JAX-RS API (plus protobuf, Jackson, SLF4J). |
| `jaxrs-twirp-protoc`         | Standalone `protoc` plugin (shaded fat-jar) that emits a Java service interface, a JAX-RS resource, and a portable JAX-RS client per service. The generated code depends only on the core. |
| `dropwizard-twirp`           | [Dropwizard 5 adapter](dropwizard-twirp/README.md) over the core: `TwirpBundle` and the managed `TwirpClientBuilder`. Pulls in the core transitively. |
| `dropwizard-twirp-example`   | End-to-end example: a Dropwizard app exposing the canonical Haberdasher Twirp service over both wire formats. See [its README](dropwizard-twirp-example/README.md) for runnable server + client demos. |

## Quickstart

### 1. Add the runtime dependency

> **Not published yet.** These artifacts aren't on Maven Central, so this
> coordinate won't resolve until you build them locally — clone this repo and
> run `mvn clean install` first (see [Building from source](#building-from-source)).

```xml
<dependency>
    <groupId>com.dennyac.twirp</groupId>
    <artifactId>jaxrs-twirp-core</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

On Dropwizard, depend on `dropwizard-twirp` instead — it brings the core along.

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
and tell it about our plugin. You also need the `kr.motd.maven:os-maven-plugin`
build extension so `${os.detected.classifier}` resolves to a protoc binary for
your platform:

```xml
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
      <goals><goal>compile</goal></goals>
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
```

A `mvn compile` emits, under `target/generated-sources/protobuf/java`:

- protoc's normal Java message classes (`Hat`, `Size`, …)
- `Haberdasher.java` — the service interface for you to implement
- `HaberdasherResource.java` — the JAX-RS resource that wraps it
- `HaberdasherClient.java` — a client stub that implements `Haberdasher`
  (skip it with the `client=false` plugin option)

### 4. Implement the service and register the resource

```java
public class HaberdasherImpl implements Haberdasher {
    @Override
    public Hat makeHat(Size request) throws TwirpException {
        if (request.getInches() <= 0) {
            throw TwirpException.invalidArgument("inches", "must be positive");
        }
        return Hat.newBuilder()
                .setInches(request.getInches())
                .setColor("red")
                .setStyleName(request.getInches() >= 10 ? "fedora" : "bowler")
                .build();
    }
}
```

Register it alongside `TwirpServerFeature`, which installs the codecs and Twirp
error handling. On Jersey that's a `ResourceConfig`; on any other JAX-RS 3.1
runtime, register the same two objects on your `Application`:

```java
ResourceConfig config = new ResourceConfig();
config.register(new TwirpServerFeature());
config.register(new HaberdasherResource(new HaberdasherImpl()));
```

That's it. Start the application and POST to it — the resource answers on both
wire formats at the same URL:

```bash
$ curl -X POST -H 'Content-Type: application/json' \
      -d '{"inches": 12}' \
      http://localhost:8080/twirp/twitch.twirp.example.haberdasher.Haberdasher/MakeHat
{"inches":12,"color":"red","style_name":"fedora"}
```

On Dropwizard, `bootstrap.addBundle(new TwirpBundle<>())` replaces the
`TwirpServerFeature` registration and the resource goes on
`environment.jersey()` — see
[`dropwizard-twirp/README.md`](dropwizard-twirp/README.md). For the client half,
see [Calling a Twirp service from Java](#calling-a-twirp-service-from-java). The
[`dropwizard-twirp-example`](dropwizard-twirp-example/README.md) module is a
complete runnable project covering both sides, with `curl` recipes for protobuf
and JSON.

## Runtime API

### `TwirpServerFeature`

The one thing a server has to register. It installs:

- `ProtobufMessageBodyReader`/`Writer` and `ProtobufJsonMessageBodyReader`/`Writer`
  for `application/protobuf` and `application/json`
- `TwirpExceptionMapper`, which turns `TwirpException` into the wire-format JSON,
  and `InvalidProtocolBufferExceptionMapper`, which turns bad wire bytes into a
  Twirp `malformed` 400
- a prefix-aware response filter that turns Twirp routing and content-type
  failures into `bad_route` 404 responses without changing ordinary REST errors

Pass a custom `JsonFormat.Printer` / `Parser` to the two-arg constructor to
change field-presence semantics, integer formatting, and the like. The defaults
(`TwirpJson.defaultPrinter()` / `defaultParser()`) match Twirp's Go reference
server: `preservingProtoFieldNames()` so `style_name` stays `style_name`,
`includingDefaultValueFields()` so consumers don't have to distinguish "absent"
from "default" for proto3 scalars, and `omittingInsignificantWhitespace()`. If
code generation uses a non-default URL prefix, pass the same value so routing
failures are recognized as Twirp requests — `new TwirpServerFeature("/rpc")`, or
the printer/parser/prefix constructor. If your messages embed a
`google.protobuf.Any`, JSON serialization additionally needs a `TypeRegistry`
that knows the packed types, applied to both the printer and the parser via
`usingTypeRegistry(...)` (binary protobuf carries the type URL inline and needs
none).

The route filter rewrites JAX-RS 404, 405, and 415 responses only when the
request path is the configured Twirp prefix or a child of it. So in a mixed
REST/Twirp application `/twirp/example.Service/Nope` becomes Twirp `bad_route`,
while `/api/missing`, a wrong method on `/api/users`, and an unsupported media
type on that REST resource keep the application's normal responses. Legitimate
service-level Twirp errors such as `not_found` are also preserved.

### `TwirpException`

Throw this from your service implementation to produce a structured Twirp
error response (HTTP status derived from the code per the Twirp spec):

```java
throw TwirpException.notFound("hat does not exist");

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

The generated resource binds `@Path("/twirp/<proto-package>.<ServiceName>")` at
the class level, `@POST @Path("/<RpcName>")` per RPC (the RPC name keeps its
original `UpperCamelCase`, matching the Twirp v7 spec), and
`@Consumes`/`@Produces` for both `application/protobuf` and `application/json`.

If a request supplies `Content-Type: application/protobuf` the response is also
protobuf; same for JSON. Twirp error responses are *always* JSON regardless of
the inbound content type — that's the spec, so clients can decode errors
without protobuf descriptors.

## Calling a Twirp service from Java

`jaxrs-twirp-protoc` also emits a `<Service>Client` per service, e.g.
`HaberdasherClient`, that implements the same service interface. Give it a
`WebTarget` rooted at the remote origin — any JAX-RS `Client` will do:

```java
Client client = ClientBuilder.newClient();
Haberdasher remote = new HaberdasherClient(client.target("https://hats.example.com"));

try {
    Hat hat = remote.makeHat(Size.newBuilder().setInches(12).build());
} catch (TwirpException ex) {
    log.warn("haberdasher failed: {} ({})", ex.getMessage(), ex.getErrorCode());
}
```

- The default wire format is `application/protobuf`. Pass
  `TwirpMediaTypes.APPLICATION_JSON` as the second constructor argument for JSON.
- The constructor calls `TwirpClients.registerProviders` on the supplied
  `WebTarget`, so there's no extra JAX-RS wiring.
- Twirp error responses (any non-2xx with a JSON body) decode back into
  `TwirpException` with the original `ErrorCode`, message, and meta map. Bodies
  that aren't a recognizable Twirp envelope fall back to `ErrorCode.UNKNOWN`
  with the HTTP status and a body snippet folded into the message.
- Transport failures (connection refused, DNS, …) surface as
  `ErrorCode.UNAVAILABLE`, and unparseable error bodies as `ErrorCode.MALFORMED`,
  rather than leaking `WebApplicationException`.
- The constructor takes the **root** target and appends the Twirp path itself,
  so an already-pathed target double-prefixes the URL. Dropwizard callers can
  use [`TwirpClientBuilder`](dropwizard-twirp/README.md#twirpclientbuilder),
  which resolves the root target and content type from a `baseUri`.

## Code generation without Maven (raw `protoc` plugin)

The `jaxrs-twirp-protoc` shaded jar is a self-contained `protoc` plugin, so any
build system that can invoke `protoc` (Gradle, Bazel, Make, plain shell) can
drive it. `protoc` just needs to find an executable named
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

Plugin options are passed as `--twirp_java_out=<key>=<value>,<key>=<value>:OUT`.
The same keys work from the Maven plugin as `<arg>` entries on the
`<protocPlugin>`; the generator merges them with protoc's option parameter.

<a id="plugin-options"></a>

| Option   | Default  | Effect                                                                                       |
| -------- | -------- | -------------------------------------------------------------------------------------------- |
| `prefix` | `/twirp` | URL path prefix prepended to every `@Path` annotation.                                       |
| `client` | `true`   | Emit a JAX-RS `<Service>Client`. Set to `false` for server-only deploys.                     |
| `server` | `true`   | Emit a JAX-RS `<Service>Resource`. Set to `false` for client-only modules (shared client jar consumed by other apps). |
| `clientBuilder` | `false` | Also emit a static `<Service>Client.builder(Environment, JerseyClientConfiguration)` factory. Couples the generated client to `dropwizard-client`; off by default. See [`dropwizard-twirp`](dropwizard-twirp/README.md#clientbuildertrue-codegen-option). |
| `context` | `false` | Append a trailing `TwirpContext` parameter carrying inbound headers and the JAX-RS `SecurityContext` to each generated interface, resource, and client method. |

Setting both `client=false` and `server=false` is rejected — the only thing
that would be emitted is the service interface, which is better expressed as
"use plain protoc, not Twirp codegen". Splitting `client`/`server` across a
published client jar and a server deploy is a common asymmetric setup; the
[example README](dropwizard-twirp-example/README.md#asymmetric-setups-client-only-or-server-only)
has the module layout and both `pom.xml` fragments.

<a id="request-context"></a>

## Request context and auth

By default a generated method is transport-free — `Hat makeHat(Size request)`.
With `context=true`, every generated service, resource, client, and
implementation method gains a trailing `TwirpContext` from `jaxrs-twirp-core`:
`Hat makeHat(Size request, TwirpContext context)`. It carries an immutable,
case-insensitive header snapshot (`header(name)`, `headerValues(name)`,
`headers()`, `outboundHeaders()`) plus JAX-RS authentication information
(`principal()`, `isUserInRole(role)`, `securityContext()`).

On the server, the generated resource injects `HttpHeaders` and
`SecurityContext`, builds the `TwirpContext`, and passes it to your
implementation. Inbound headers are never forwarded implicitly; on the client,
`TwirpContext.ofOutboundHeaders(Map.of("Authorization", List.of("Bearer " +
token)))` supplies an explicit outbound set. That factory rejects
transport-controlled and hop-by-hop headers such as `Content-Type`,
`Content-Length`, `Host`, `Connection`, and `Transfer-Encoding`; authorization,
trace, and application metadata remain available when selected explicitly.

Generated methods declare no framework auth parameter, so bind your
`ContainerRequestFilter` with `TwirpAuthFeature`, which resolves the generated
`@Path("/WhoAmI")` at startup and rejects unknown RPC names rather than
silently leaving them unprotected:

```java
config.register(TwirpAuthFeature.forRpcs(authFilter, HaberdasherResource.class, "WhoAmI"));
```

The filter populates the JAX-RS `SecurityContext`, which `principal()` and
`isUserInRole(...)` then expose. For dropwizard-auth see
[`dropwizard-twirp/README.md`](dropwizard-twirp/README.md#dropwizard-auth); the
[example](dropwizard-twirp-example/README.md#request-context--auth) has a
complete `OAuthCredentialAuthFilter` walkthrough.

`context` is off by default because it changes the shared Java interface, and
clients and servers must use the same setting. Header values are safe
snapshots; the underlying `SecurityContext` remains request-scoped.
`TwirpContext` does not currently model cancellation or deadlines.

## How this compares to other Java Twirp options

Twirp is Go-first; on the JVM it's a patchwork of community projects, so it's
worth knowing where this one sits.

| Project | Server it targets | Client | Codegen driver | On Maven Central |
| ------- | ----------------- | ------ | -------------- | ---------------- |
| **jaxrs-twirp** (this repo) | Jakarta REST 3.1 (Jersey, RESTEasy, …) | Portable JAX-RS stub that decodes Twirp errors | `protoc` plugin (Maven or raw) | not yet (`0.1.0-SNAPSHOT`) |
| [github/flit][flit] | JAX-RS/Jakarta REST, Spring, Undertow | none | `protoc` plugin | yes |
| [ngyewch/protoc-gen-twirp-java][ngyewch] | Helidon SE | Apache HttpClient | `protoc` plugin (Gradle) | yes |
| Twitch's `protoc-gen-twirp_java` | — | — | experimental, never finished | no |

There is no official Spring Boot Twirp starter; flit generates Spring server
bindings but no client. This repo won't drop into a Spring MVC app either — the
generated resource is `jakarta.ws.rs`, which runs on Jersey or RESTEasy but not
Spring MVC.

[ngyewch]: https://github.com/ngyewch/protoc-gen-twirp-java
[flit]: https://github.com/github/flit

## Supported proto features & limitations

Code generation works at the service boundary: the plugin resolves the Java
class names of each RPC's request/response message and leaves field
serialization to protoc's standard Java output and
`com.google.protobuf.util.JsonFormat`. Message-level complexity is therefore
handled by the protobuf runtime, and the gaps below are about the *service*
layer and JSON edge cases.

### Handled

| Feature / edge case | Status | Notes |
|---|---|---|
| Enums, nested messages, `repeated`, `map<k,v>`, `oneof` | ✅ | Pure protoc Java codegen; serialized per the proto3 JSON spec. Covered end-to-end by the example's `ListInventory` over both wire formats. |
| Imports / cross-file message references | ✅ | `TypeMapper` is built from *all* descriptors protoc passes (imports + well-known types), so an RPC can use a message from another `.proto` and still resolve to the right `java_package`. |
| Multiple services in one `.proto` | ✅ | The plugin iterates every service in the file. |
| Proto3 `optional` (field presence) | ✅ | Plugin advertises `FEATURE_PROTO3_OPTIONAL`. |
| `google.protobuf.Empty` and other well-known types as request/response | ✅ | Resolved through the same transitive-descriptor lookup. |
| `Timestamp`, `Duration`, `Struct`, `Value`, `FieldMask`, wrappers in JSON | ✅ | `JsonFormat` renders these natively, no registry needed. |
| Configurable URL prefix | ✅ | `prefix` generator option (defaults to `/twirp`, per Twirp v7). |
| JSON snake_case names / unknown-field tolerance / default-value emission | ✅ | Go-reference-compatible defaults; override with a custom printer/parser. |
| RPC names that lowercase to a Java keyword (`Return`, `Import`, …) | ✅ | The generated Java method is suffixed with `_` (e.g. `return_`); the URL path keeps the original proto name, so wire compatibility is unaffected. |
| Unroutable requests (unknown URL, non-`POST`, unsupported content type) | ✅ | Returned as Twirp `bad_route` JSON only beneath configured Twirp prefixes; REST 404/405/415 responses are preserved. |

### Needs configuration

| Feature / edge case | Status | What to do |
|---|---|---|
| `google.protobuf.Any` over **JSON** | ⚠️ | `JsonFormat` cannot resolve a packed `Any` to JSON without a `TypeRegistry`. Register the packed types on the printer and parser you hand to `TwirpServerFeature` (see above). Binary protobuf needs nothing — the type URL travels inline. |

### Not supported (by design or not yet)

| Feature / edge case | Status | Notes |
|---|---|---|
| Streaming RPCs | ❌ by design | Twirp itself has no streaming — it's a non-goal of the protocol. The generator rejects streaming methods at codegen time with a clear error rather than emitting something that can't work over unary HTTP. |
| Protobuf **editions** (`edition = "2023"`) | ❌ not yet | The plugin does not yet declare `FEATURE_SUPPORTS_EDITIONS`, so protoc 25+ refuses to run it on editions files. Stay on `syntax = "proto3"` for now. (Editions appear to need no Twirp-specific codegen changes, so this is a declaration/testing gap, not a design limit.) |
| `[json_name = "..."]` field option | ⚠️ intentionally ignored | With `preservingProtoFieldNames()` the raw proto field name wins, matching Go Twirp's `UseProtoNames: true`. If you need `json_name` honored, supply a custom printer/parser without name preservation — at the cost of diverging from the Go server. |
| proto2 `required` field missing on decode | ⚠️ maps to `internal` | A missing `required` field throws after decode and surfaces as a Twirp `internal` (HTTP 500) rather than `malformed` (400). proto3 has no `required`, so this only affects proto2 schemas. |

The `Any`-over-JSON behavior is verified by `AnyJsonCodecTest`, the
keyword-mangling by `JavaNamingTest`, and the route-scoped `bad_route` handling
by `ExampleApplicationIntegrationTest`.

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
- Adapters for other JAX-RS runtimes alongside `dropwizard-twirp`.

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

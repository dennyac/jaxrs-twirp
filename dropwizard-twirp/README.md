# dropwizard-twirp

[Dropwizard 5](https://www.dropwizard.io/) adapter for
[jaxrs-twirp](../README.md). Two classes:

- `TwirpBundle` — installs the `jaxrs-twirp-core` body providers, exception
  mappers, and `bad_route` filter on Dropwizard's Jersey environment.
- `TwirpClientBuilder` — builds a generated `<Service>Client` against a managed
  JAX-RS `Client`, or against one it creates from `JerseyClientConfiguration`.

Everything protocol-level — writing the `.proto`, running code generation, the
wire format, `TwirpException`, `TwirpContext` — is in the
[top-level README](../README.md). This page covers only the Dropwizard wiring.

## Add the dependency

> **Not published yet.** These artifacts aren't on Maven Central, so this
> coordinate won't resolve until you build them locally — clone this repo and
> run `mvn clean install` first (see
> [Building from source](../README.md#building-from-source)).

```xml
<dependency>
    <groupId>com.dennyac.twirp</groupId>
    <artifactId>dropwizard-twirp</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

That pulls in `jaxrs-twirp-core` transitively; a Dropwizard app needs nothing
else on the server side.

## Serve a Twirp service

Write the `.proto` and wire up code generation as in the
[top-level quickstart](../README.md#quickstart), implement the generated
service interface, then add the bundle and register the generated resource:

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

Start the app and POST to it:

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

The RPC endpoints run on the app's existing Jetty connector and share its
request logging, metrics, filters, and lifecycle with the REST resources.

## `TwirpBundle`

A `ConfiguredBundle<C extends Configuration>` that registers everything needed
to speak Twirp:

- `ProtobufMessageBodyReader` / `Writer` for `application/protobuf`
- `ProtobufJsonMessageBodyReader` / `Writer` for `application/json`
- `TwirpExceptionMapper` — turns `TwirpException` into the wire-format JSON
- `InvalidProtocolBufferExceptionMapper` — turns bad-wire-bytes into a
  Twirp `malformed` 400 response
- a prefix-aware response filter that turns Twirp routing and content-type
  failures into `bad_route` 404 responses without changing ordinary REST errors

That's the same set `TwirpServerFeature` installs on plain JAX-RS — the bundle
just applies it to `environment.jersey()`.

### JSON printer and parser

Customize field-presence semantics, integer formatting, and so on via the
builder:

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

### Non-default URL prefix

If code generation uses a non-default URL prefix, configure the same prefix on
the bundle so routing failures are recognized as Twirp requests:

```java
bootstrap.addBundle(TwirpBundle.builder()
        .pathPrefix("/rpc")
        .<MyConfig>build());
```

### `google.protobuf.Any` over JSON

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

See
[Supported proto features & limitations](../README.md#supported-proto-features--limitations)
for the full matrix of what the codegen and runtime handle.

## `TwirpClientBuilder`

The generated client's raw constructor wants the **root** `WebTarget` (the bare
remote origin) — it appends the Twirp path itself, so an already-pathed target
double-prefixes the URL. `TwirpClientBuilder` takes a managed `Client` (or an
`Environment` plus a `JerseyClientConfiguration`) and a `baseUri`, and resolves
the root target and content type for you:

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

Dropwizard's `JerseyClientBuilder` is the usual way to get the underlying
`Client`: you get metrics, Apache HttpClient, configurable timeouts, and
lifecycle management for free. The `using(environment, configuration)` overload
needs `io.dropwizard:dropwizard-client`, which this module declares as an
**optional** dependency — server-only apps never pull it onto their classpath,
and you add it yourself only when you build clients this way.

### `clientBuilder=true` codegen option

To call `HaberdasherClient.builder(environment, configuration)` directly — the
sugar form — run codegen with `clientBuilder=true` and the generator emits that
static factory on the client, delegating to `TwirpClientBuilder`:

```xml
<!-- in the twirp_java <protocPlugin> -->
<args>
    <arg>clientBuilder=true</arg>
</args>
```

It's **off by default** because it bakes a compile-time reference to
`dropwizard-client` into the generated client; leaving it off keeps the
generated stub on the JAX-RS API alone, usable from any framework. Either way
the raw `WebTarget` constructors stay as the escape hatch.

## dropwizard-auth

Generated methods do not declare an `@Auth` parameter, so bind the auth filter
with `TwirpAuthFeature` (from `jaxrs-twirp-core`) instead:

```java
environment.jersey().register(TwirpAuthFeature.forRpcs(
        authFilter,
        HaberdasherResource.class,
        "WhoAmI"));
```

The helper resolves the generated `@Path("/WhoAmI")` at startup and rejects
unknown RPC names rather than silently leaving them unprotected. The filter
populates the JAX-RS `SecurityContext`, which `TwirpContext.principal()` and
`isUserInRole(...)` then expose to the service implementation when codegen runs
with `context=true` — see
[Request context and auth](../README.md#request-context) for the generic half.
The [example module](../dropwizard-twirp-example/README.md#request-context--auth)
has the complete `OAuthCredentialAuthFilter` setup.

## See also

- [`dropwizard-twirp-example`](../dropwizard-twirp-example/README.md) — a
  runnable Dropwizard app serving the Haberdasher service over both wire
  formats, with `curl` recipes and end-to-end tests.
- [Top-level README](../README.md) — code generation, wire format, plugin
  options, and the plain-JAX-RS path.

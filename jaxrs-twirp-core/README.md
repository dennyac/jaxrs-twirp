# jaxrs-twirp-core

The Jakarta REST runtime for [jaxrs-twirp][overview]. It provides protobuf and
JSON message codecs, Twirp error types and mappers, client helpers, and request
context/auth support. It has no Dropwizard dependency and does not include a
JAX-RS server or client implementation.

## Dependency

Use Java 17+ and a Jakarta REST 3.1 implementation. Follow the
[runtime dependency instructions][dependency] to install the unpublished
`0.1.0-SNAPSHOT` artifacts locally and add the core to your application.
These are pre-release APIs and may change.

## Server registration

Register `TwirpServerFeature` once in your application alongside its generated
resources, as in the [quickstart][quickstart]. The feature installs:

- `ProtobufMessageBodyReader` and `ProtobufMessageBodyWriter` for binary messages.
- `ProtobufJsonMessageBodyReader` and `ProtobufJsonMessageBodyWriter` for JSON messages.
- `TwirpExceptionMapper` and `InvalidProtocolBufferExceptionMapper` for Twirp errors.
- A response filter that maps routing failures under the configured prefixes to
  `bad_route` (HTTP 404).

The default prefix is `/twirp`. When generating resources with a custom
`prefix`, use the same value when registering the feature:

```java
TwirpServerFeature feature = new TwirpServerFeature("/rpc");
```

The constructors also accept multiple prefixes, for example
`new TwirpServerFeature("/twirp", "/rpc")`. The printer/parser constructor accepts
prefixes after those two arguments.

Routing failures with status 404, 405, or 415 are rewritten only at a configured
prefix or below it. Routes outside those prefixes keep their normal REST
responses, and existing `TwirpError` responses such as `not_found` are preserved.
A prefix of `/` covers all routes, including REST routes.

## Wire format

Generated resources use a class-level path of
`/twirp/<proto-package>.<ServiceName>` and a `POST /<RpcName>` method for each
RPC. A custom generator prefix replaces `/twirp`. Without a proto package,
the service name appears without a package or leading dot.

RPC paths retain the original proto spelling, such as `MakeHat`, even though
the Java method is named `makeHat`. Requests and successful responses use
`application/protobuf` or `application/json`; the generated resource responds
in the request's format. No `Accept` header is needed.

The [Twirp v7 protocol][spec] requires errors to use `application/json`
regardless of the request format:

```json
{"code":"invalid_argument","msg":"inches must be positive","meta":{"argument":"inches"}}
```

## Errors

Throw `TwirpException` from a service implementation. Its `ErrorCode` determines
the HTTP status, and `getMeta()` exposes string-valued metadata:

```java
throw TwirpException.builder(ErrorCode.UNAVAILABLE)
        .message("warehouse unavailable")
        .meta("retry_after", "15s")
        .build();
```

Convenience factories include `TwirpException.invalidArgument(argument, reason)`,
`notFound(message)`, `internal(message, cause)`, and `unimplemented(method)`.

Generated resources call services through `TwirpInvocations.invoke`.
It preserves `TwirpException`, maps `InterruptedException` to `UNAVAILABLE`
while restoring the interrupt flag, and wraps other `Exception` instances as
`INTERNAL`. Java `Error` instances propagate unchanged.

`InvalidProtocolBufferExceptionMapper` maps malformed protobuf/JSON input to
`malformed` (HTTP 400). Missing proto2 required fields are a separate
[known limitation][runtime-limitations].

## Clients

A generated `<Service>Client` implements the service interface. This client
calls the [quickstart][quickstart] service using a JAX-RS client implementation
on the application's classpath:

```java
package com.example.haberdasher;

import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;

public class HatClientDemo {
    public static void main(String[] args) {
        try (Client client = ClientBuilder.newClient()) {
            Haberdasher remote = new HaberdasherClient(
                    client.target("http://localhost:8080"));
            Hat hat = remote.makeHat(Size.newBuilder().setInches(12).build());
            System.out.println(hat.getStyleName());
        }
    }
}
```

The target must point to the application's base URL, without the Twirp service
path: the constructor appends that path itself. If the application is mounted
under `/api`, include `/api` in the base URL, but not `/twirp/...`.

Binary protobuf is the default. Pass `TwirpMediaTypes.APPLICATION_JSON` as the
second constructor argument for JSON. The generated constructor calls
`TwirpClients.registerProviders` on the target.

Calls throw `TwirpException` for these failure cases:

| Failure | Error code |
| --- | --- |
| Recognizable Twirp error response | The server's code, message, and metadata |
| Non-2xx response without a recognizable Twirp envelope | `UNKNOWN`, with the HTTP status and a body excerpt |
| Transport or request-encoding `ProcessingException` | `UNAVAILABLE` |
| Successful response that cannot be decoded | `MALFORMED` |

Dropwizard applications can use the [managed client builder][managed-client].

## JSON configuration

`TwirpJson.defaultPrinter()` preserves proto field names such as `style_name`,
includes default-valued fields, and omits insignificant whitespace.
`TwirpJson.defaultParser()` ignores unknown fields.

The default printer uses the proto field name rather than a field's explicit
`json_name`. To use protobuf's JSON names and reject unknown input fields,
register a feature configured with a different printer and parser:

```java
TwirpServerFeature feature = new TwirpServerFeature(
        JsonFormat.printer()
                .includingDefaultValueFields()
                .omittingInsignificantWhitespace(),
        JsonFormat.parser());
```

Here `JsonFormat` is `com.google.protobuf.util.JsonFormat`. These choices change
the default naming and parsing behavior; they do not change the binary format.

### Any over JSON

`google.protobuf.Any` requires a `com.google.protobuf.TypeRegistry` containing
the packed message types on both the JSON printer and parser. Binary protobuf
does not require a registry to transport an `Any`.

For example, to allow a packed `Hat` from the quickstart:

```java
TypeRegistry registry = TypeRegistry.newBuilder()
        .add(Hat.getDescriptor())
        .build();
JsonFormat.Printer printer = TwirpJson.defaultPrinter().usingTypeRegistry(registry);
JsonFormat.Parser parser = TwirpJson.defaultParser().usingTypeRegistry(registry);
TwirpServerFeature feature = new TwirpServerFeature(printer, parser);
```

Register `feature` in place of the default server feature. On a client, register
the same configured codecs before constructing the generated client:

```java
try (Client client = ClientBuilder.newClient()) {
    TwirpClients.registerProviders(client, printer, parser);
    Haberdasher remote = new HaberdasherClient(
            client.target("http://localhost:8080"),
            TwirpMediaTypes.APPLICATION_JSON);
    Hat hat = remote.makeHat(Size.newBuilder().setInches(12).build());
    System.out.println(hat.getStyleName());
}
```

Other well-known types such as `Timestamp`, `Duration`, `Struct`, `Value`,
`FieldMask`, and wrappers use `JsonFormat`'s built-in mappings.

## Request context and auth

Generate with [`context=true`][options] to change service methods from
`Hat makeHat(Size request)` to
`Hat makeHat(Size request, TwirpContext context)`. Use the same setting for
client and server code generation.

The generated server resource builds `TwirpContext` from JAX-RS `HttpHeaders`
and `SecurityContext`. It exposes:

- `header(name)`, `headerValues(name)`, and `headers()` for an immutable,
  case-insensitive header snapshot.
- `principal()`, `isUserInRole(role)`, and `securityContext()` for authentication.
- `outboundHeaders()` for headers explicitly selected for a client call.

Inbound headers are never forwarded automatically. Create an outbound context
and pass it as the trailing argument when calling a generated client method:

```java
TwirpContext outbound = TwirpContext.ofOutboundHeaders(
        Map.of("X-Request-ID", List.of("request-123")));
```

`Map` and `List` are from `java.util`. Authorization and application metadata
can be selected this way too. The factory rejects transport-controlled and
hop-by-hop headers such as `Content-Type`, `Content-Length`, `Host`,
`Connection`, and `Transfer-Encoding`. Use `TwirpContext.empty()` when there
are no headers to send.

`TwirpAuthFeature` binds an application's `ContainerRequestFilter` to generated
RPCs. Given your authentication filter as `authFilter`, create and register
this feature alongside the resource:

```java
TwirpAuthFeature auth = TwirpAuthFeature.forRpcs(
        authFilter, HaberdasherResource.class, "MakeHat");
```

Names are proto RPC names, not Java method names. Unknown RPC names fail during
startup. `forAllRpcs(authFilter, resourceClass)` protects every RPC in a
resource; `builder(authFilter).protect(...).protectAll(...).build()` supports
multiple resources.

The authentication filter remains responsible for setting `SecurityContext`
and returning a Twirp JSON response when rejecting a request. See the
[Dropwizard auth example][auth-example] for bearer tokens and role checks.

## Runtime limitations

Missing proto2 `required` fields can surface as a server error (HTTP 500)
instead of Twirp `malformed` (HTTP 400). This is a known implementation issue,
not a Twirp protocol restriction.

The codecs require full protobuf Java `Message` classes, not lite
`MessageLite` output. `TwirpContext` does not model cancellation or deadlines;
its header snapshot may be retained, but its `SecurityContext` is request-scoped.
See also the generator's [proto limitations][proto-limitations].

[overview]: https://github.com/dennyac/jaxrs-twirp/blob/main/README.md
[dependency]: https://github.com/dennyac/jaxrs-twirp/blob/main/README.md#1-add-the-runtime-dependency
[quickstart]: https://github.com/dennyac/jaxrs-twirp/blob/main/README.md#quickstart
[spec]: https://twitchtv.github.io/twirp/docs/spec_v7.html
[runtime-limitations]: https://github.com/dennyac/jaxrs-twirp/blob/main/jaxrs-twirp-core/README.md#runtime-limitations
[managed-client]: https://github.com/dennyac/jaxrs-twirp/blob/main/dropwizard-twirp/README.md#twirpclientbuilder
[options]: https://github.com/dennyac/jaxrs-twirp/blob/main/jaxrs-twirp-protoc/README.md#generator-options
[auth-example]: https://github.com/dennyac/jaxrs-twirp/blob/main/dropwizard-twirp-example/README.md#request-context-and-auth
[proto-limitations]: https://github.com/dennyac/jaxrs-twirp/blob/main/jaxrs-twirp-protoc/README.md#proto-support-and-limitations

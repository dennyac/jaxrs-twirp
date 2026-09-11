# jaxrs-twirp

Client and server code generation for [Twirp][] RPC on Jakarta REST (JAX-RS).
Define a service in a `.proto` file to generate a Java service interface,
a JAX-RS resource, and a client. Implement the interface and register the
resource alongside your existing REST endpoints.

The core runtime has no Dropwizard dependency. Dropwizard is one adapter;
other Jakarta REST applications use the core directly.

## What is Twirp?

Twirp is a request/response RPC protocol from Twitch. Each call is an HTTP
`POST` with a protobuf or JSON body; errors use a JSON envelope and an HTTP
status code. HTTP/2 is not required: Twirp supports any HTTP version.
Streaming is excluded by the Twirp protocol, not a missing feature of this
implementation. See the [wire-format details][wire-format].

## Modules

| Module | Purpose |
| --- | --- |
| [`jaxrs-twirp-core`][core] | Runtime providers, errors, clients, JSON configuration, and request context/auth. |
| [`jaxrs-twirp-protoc`][generator] | `protoc` plugin; Maven/raw `protoc` setup, generator options, and proto limitations. |
| [`dropwizard-twirp`][dropwizard] | Dropwizard 5 bundle and managed client builder. |
| [`dropwizard-twirp-example`][example] | Runnable Haberdasher server with JSON, protobuf, and authentication examples. |

## Compatibility

Requires **Java 17+** and a **Jakarta REST 3.1** implementation; source builds
require **Maven 3.9+**. The build uses protobuf 4.32.1.

Protobuf editions are unsupported. `google.protobuf.Any` over JSON requires a
[`TypeRegistry`][any-json]. Decoding proto2 messages with missing `required`
fields has a known error-mapping issue. See the [detailed limitations][limitations].

## Quickstart

This adds a service to an existing Jakarta REST application. The core does not
include an HTTP server; for a runnable application, use the [Dropwizard example][example].

### 1. Add the runtime dependency

These are unpublished pre-release artifacts (`0.1.0-SNAPSHOT`), and APIs may
change. [Build and install them locally][build] before using these coordinates.

```xml
<dependency>
    <groupId>com.dennyac.twirp</groupId>
    <artifactId>jaxrs-twirp-core</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

For Dropwizard, use the [adapter dependency and registration instructions][dropwizard]
instead; it includes the core transitively.

### 2. Define and generate the service

Create `src/main/proto/haberdasher.proto` in your application:

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

Add the [Maven generator configuration][maven] to your application's `pom.xml`,
then run `mvn compile`. This generates `Hat`, `Size`, `Haberdasher`,
`HaberdasherResource`, and `HaberdasherClient` under
`target/generated-sources/protobuf/java`.

### 3. Implement the service

Create `src/main/java/com/example/haberdasher/HaberdasherImpl.java`:

```java
package com.example.haberdasher;

import com.dennyac.twirp.TwirpException;

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

### 4. Register and call the resource

Register `TwirpServerFeature` and the generated resource with your application.
For a standard Jakarta REST `Application`, create
`src/main/java/com/example/haberdasher/HatApplication.java`:

```java
package com.example.haberdasher;

import com.dennyac.twirp.TwirpServerFeature;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import java.util.Set;

@ApplicationPath("/")
public class HatApplication extends Application {
    @Override
    public Set<Object> getSingletons() {
        return Set.of(
                new TwirpServerFeature(),
                new HaberdasherResource(new HaberdasherImpl()));
    }
}
```

If you already have an application registration class, add these two instances
there rather than creating another one. Build with `mvn package` and deploy
using your Jakarta REST server. With the application mounted at
`http://localhost:8080`, call it with:

```bash
curl -s -H 'Content-Type: application/json' \
  -d '{"inches": 12}' \
  http://localhost:8080/twirp/twitch.twirp.example.haberdasher.Haberdasher/MakeHat
```

Response:

```json
{"inches":12,"color":"red","style_name":"fedora"}
```

For Java callers, see [generated clients][clients]. The [core guide][core]
covers errors, JSON configuration, and authentication.

## Building from source

With JDK 17+ and Maven 3.9+, run from the repository root:

```bash
mvn clean install
```

The Maven build downloads `protoc` for your platform; a system installation
is not required.

## License

Licensed under the [Apache License 2.0][license]; see [NOTICE][] for attribution.
This is an independent implementation and is not affiliated with or endorsed by Twitch.

[Twirp]: https://github.com/twitchtv/twirp
[core]: https://github.com/dennyac/jaxrs-twirp/blob/main/jaxrs-twirp-core/README.md
[generator]: https://github.com/dennyac/jaxrs-twirp/blob/main/jaxrs-twirp-protoc/README.md
[dropwizard]: https://github.com/dennyac/jaxrs-twirp/blob/main/dropwizard-twirp/README.md
[example]: https://github.com/dennyac/jaxrs-twirp/blob/main/dropwizard-twirp-example/README.md
[wire-format]: https://github.com/dennyac/jaxrs-twirp/blob/main/jaxrs-twirp-core/README.md#wire-format
[any-json]: https://github.com/dennyac/jaxrs-twirp/blob/main/jaxrs-twirp-core/README.md#any-over-json
[limitations]: https://github.com/dennyac/jaxrs-twirp/blob/main/jaxrs-twirp-protoc/README.md#proto-support-and-limitations
[maven]: https://github.com/dennyac/jaxrs-twirp/blob/main/jaxrs-twirp-protoc/README.md#maven
[clients]: https://github.com/dennyac/jaxrs-twirp/blob/main/jaxrs-twirp-core/README.md#clients
[build]: https://github.com/dennyac/jaxrs-twirp/blob/main/README.md#building-from-source
[license]: https://github.com/dennyac/jaxrs-twirp/blob/main/LICENSE
[NOTICE]: https://github.com/dennyac/jaxrs-twirp/blob/main/NOTICE

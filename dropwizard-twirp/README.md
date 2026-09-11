# dropwizard-twirp

The [Dropwizard 5][dropwizard] adapter for [jaxrs-twirp][overview].
`TwirpBundle` registers the core's server feature with Jersey;
`TwirpClientBuilder` connects generated clients to existing or managed
JAX-RS clients.

## Add the dependency

These are unpublished pre-release artifacts (`0.1.0-SNAPSHOT`), and APIs may
change. [Build and install them locally][build] before using this dependency:

```xml
<dependency>
    <groupId>com.dennyac.twirp</groupId>
    <artifactId>dropwizard-twirp</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

It includes `jaxrs-twirp-core` transitively.

## Serve a Twirp service

Use the schema, generator setup, and `HaberdasherImpl` from the
[quickstart][quickstart]. Replace its Jakarta REST application registration
with this Dropwizard application:

```java
package com.example.haberdasher;

import com.dennyac.twirp.TwirpBundle;
import io.dropwizard.core.Application;
import io.dropwizard.core.Configuration;
import io.dropwizard.core.setup.Bootstrap;
import io.dropwizard.core.setup.Environment;

public class HatApplication extends Application<Configuration> {
    public static void main(String[] args) throws Exception {
        new HatApplication().run(args);
    }

    @Override
    public void initialize(Bootstrap<Configuration> bootstrap) {
        bootstrap.addBundle(new TwirpBundle<>());
    }

    @Override
    public void run(Configuration configuration, Environment environment) {
        environment.jersey().register(new HaberdasherResource(new HaberdasherImpl()));
    }
}
```

RPC endpoints use the application's existing Jetty connector and Jersey
environment. The [example application][example] provides launch configuration
and `curl` recipes.

## `TwirpBundle`

`TwirpBundle<C extends Configuration>` registers [`TwirpServerFeature`][server]
with `environment.jersey()`. Do not register a second server feature alongside
the bundle.

### JSON printer and parser

The bundle uses the core's [JSON defaults][json]. In `initialize`, customize
them through `jsonPrinter(...)` and `jsonParser(...)`, for example to reject
unknown JSON fields while retaining the default printer:

```java
bootstrap.addBundle(TwirpBundle.builder()
        .jsonParser(JsonFormat.parser())
        .<Configuration>build());
```

`JsonFormat` is `com.google.protobuf.util.JsonFormat`; use your application's
configuration type in place of `Configuration` if it has a custom subclass.

### Non-default URL prefix

If the generator uses `prefix=/rpc`, configure the matching prefix so routing
failures are recognized as Twirp requests:

```java
bootstrap.addBundle(TwirpBundle.builder()
        .pathPrefix("/rpc")
        .<Configuration>build());
```

Use `pathPrefixes("/twirp", "/rpc")` when serving resources with both prefixes.

### Any over JSON

The bundle's `typeRegistry(...)` applies a
`com.google.protobuf.TypeRegistry` to both JSON codecs. For a packed `Hat`:

```java
bootstrap.addBundle(TwirpBundle.builder()
        .typeRegistry(TypeRegistry.newBuilder()
                .add(Hat.getDescriptor())
                .build())
        .<Configuration>build());
```

Supply the registry either here or on custom printer/parser instances, not
both. See [Any over JSON][any-json] for the runtime requirements.

## `TwirpClientBuilder`

Given an existing JAX-RS `Client` named `client`:

```java
Haberdasher remote = TwirpClientBuilder.forService(HaberdasherClient::new)
        .using(client)
        .baseUri("https://hats.example.com")
        .json()
        .build();
```

The caller retains responsibility for that client's lifecycle. Alternatively,
create a Dropwizard-managed client from the `Environment` passed to `run`:

```java
Haberdasher remote = TwirpClientBuilder.forService(HaberdasherClient::new)
        .using(environment, new JerseyClientConfiguration())
        .baseUri("https://hats.example.com")
        .clientName("haberdasher")
        .build();
```

The default format is protobuf; `json()` selects JSON. `baseUri` is the
application's base URL without the Twirp service path. Give each managed client
a unique `clientName` within an environment.

The managed overload uses `io.dropwizard.client.JerseyClientConfiguration`
and requires `dropwizard-client`, an optional dependency of this module.
Add it explicitly when using that overload:

```xml
<dependency>
    <groupId>io.dropwizard</groupId>
    <artifactId>dropwizard-client</artifactId>
    <version>5.0.2</version>
</dependency>
```

To generate a `HaberdasherClient.builder(environment, configuration)` factory,
see the [`clientBuilder` generator option][options]. The standard `WebTarget`
constructors remain available with either setting.

## dropwizard-auth

Generated methods do not declare an `@Auth` parameter. Register
`TwirpAuthFeature` on `environment.jersey()` to bind your authentication filter
to selected proto RPC names. Generate with `context=true` to expose the
authenticated principal and roles to service implementations.

See the core's [request context and auth guide][context] for the API, and the
[example's auth setup][auth-example] for `OAuthCredentialAuthFilter` wiring and
bearer-token requests.

[dropwizard]: https://www.dropwizard.io/
[overview]: https://github.com/dennyac/jaxrs-twirp/blob/main/README.md
[build]: https://github.com/dennyac/jaxrs-twirp/blob/main/README.md#building-from-source
[quickstart]: https://github.com/dennyac/jaxrs-twirp/blob/main/README.md#quickstart
[example]: https://github.com/dennyac/jaxrs-twirp/blob/main/dropwizard-twirp-example/README.md
[server]: https://github.com/dennyac/jaxrs-twirp/blob/main/jaxrs-twirp-core/README.md#server-registration
[json]: https://github.com/dennyac/jaxrs-twirp/blob/main/jaxrs-twirp-core/README.md#json-configuration
[any-json]: https://github.com/dennyac/jaxrs-twirp/blob/main/jaxrs-twirp-core/README.md#any-over-json
[options]: https://github.com/dennyac/jaxrs-twirp/blob/main/jaxrs-twirp-protoc/README.md#generator-options
[context]: https://github.com/dennyac/jaxrs-twirp/blob/main/jaxrs-twirp-core/README.md#request-context-and-auth
[auth-example]: https://github.com/dennyac/jaxrs-twirp/blob/main/dropwizard-twirp-example/README.md#request-context-and-auth

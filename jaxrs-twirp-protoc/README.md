# jaxrs-twirp-protoc

A `protoc` plugin that generates a Java service interface, a Jakarta REST
resource, and a JAX-RS client for each Twirp service. Message classes come from
protoc's standard Java generator.

The plugin is a self-contained JAR requiring Java 17+. Generated code uses
[`jaxrs-twirp-core`][core] and Jakarta REST 3.1. The default output has no
Dropwizard dependency.

## Maven

The artifacts are unpublished pre-releases (`0.1.0-SNAPSHOT`), and APIs may
change. [Build and install the repository locally][build] before using the
plugin coordinates below.

In your application, add the [core dependency][dependency] and put `.proto`
files under `src/main/proto`. Merge this configuration into your `pom.xml`'s
`<build>` element:

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
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-compiler-plugin</artifactId>
      <version>3.14.1</version>
      <configuration>
        <release>17</release>
      </configuration>
    </plugin>
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
  </plugins>
</build>
```

Run `mvn compile` using Maven 3.9+. The
[protobuf-maven-plugin][protobuf-maven-plugin] downloads a platform-specific
`protoc` executable and adds `target/generated-sources/protobuf/java` as a
source directory. Its `compile` goal generates both messages and Twirp code.
The `os-maven-plugin` extension supplies `${os.detected.classifier}`.

For the [quickstart schema][quickstart], the output includes:

| File | Purpose |
| --- | --- |
| `Hat.java`, `Size.java` | Protobuf messages |
| `Haberdasher.java` | Service interface |
| `HaberdasherResource.java` | JAX-RS resource wrapping an implementation |
| `HaberdasherClient.java` | JAX-RS client implementing the interface |

## Raw protoc

Gradle, Bazel, Make, or shell scripts can invoke the same plugin. Unlike the
Maven configuration above, this requires `protoc` on your `PATH`.

First, from this repository's root, build the plugin and record its absolute
JAR path:

```bash
mvn -pl jaxrs-twirp-protoc -am package
export TWIRP_PLUGIN_JAR="$PWD/jaxrs-twirp-protoc/target/jaxrs-twirp-protoc-0.1.0-SNAPSHOT.jar"
```

In your application's root directory, create `protoc-gen-twirp_java` containing:

```sh
#!/usr/bin/env sh
exec java -jar "$TWIRP_PLUGIN_JAR" "$@"
```

In the same shell, with `TWIRP_PLUGIN_JAR` still exported, run this for the
quickstart's `src/main/proto/haberdasher.proto`:

```bash
chmod +x protoc-gen-twirp_java
mkdir -p target/generated-sources/protobuf/java
protoc \
  --plugin=protoc-gen-twirp_java="$PWD/protoc-gen-twirp_java" \
  --java_out=target/generated-sources/protobuf/java \
  --twirp_java_out=target/generated-sources/protobuf/java \
  -I src/main/proto \
  haberdasher.proto
```

Both output flags are needed: `--java_out` generates messages and
`--twirp_java_out` generates Twirp interfaces, resources, and clients. Add the
output directory to your build's Java source directories and the core runtime
to its dependencies. Add `-I` directories for any imported schemas.

Alternatively, put the executable wrapper on `PATH` under the name
`protoc-gen-twirp_java` and omit `--plugin`.

## Generator options

These are all the Twirp-specific options:

| Option | Default | Effect |
| --- | --- | --- |
| `prefix` | `/twirp` | URL prefix for resources and clients. A leading slash is added when needed; trailing slashes are removed except for `/`. An empty value selects no prefix. |
| `client` | `true` | Emit `<Service>Client`; set `false` for interface and resource output only. |
| `server` | `true` | Emit `<Service>Resource`; set `false` for interface and client output only. |
| `clientBuilder` | `false` | Add `<Service>Client.builder(Environment, JerseyClientConfiguration)`. Requires `dropwizard-twirp` and `dropwizard-client`; has no effect when `client=false`. |
| `context` | `false` | Add a trailing `TwirpContext` to service and client methods; the resource supplies it from JAX-RS request data. |

Pass options to raw protoc as
`--twirp_java_out=prefix=/rpc,context=true:target/generated-sources/protobuf/java`.
For Maven, replace the `<protocPlugin>` element above with a configured one:

```xml
<protocPlugin>
  <id>twirp_java</id>
  <groupId>com.dennyac.twirp</groupId>
  <artifactId>jaxrs-twirp-protoc</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <mainClass>com.dennyac.twirp.protoc.Main</mainClass>
  <args>
    <arg>prefix=/rpc</arg>
    <arg>context=true</arg>
  </args>
</protocPlugin>
```

Use `true` or `false` for booleans; `yes`/`no` and `1`/`0` are also accepted.
Unknown keys are ignored, and unrecognized boolean values fall back to the
default. Command-line arguments are appended to protoc's parameter string;
the last value wins when a key is repeated.

Configure the same prefix on [`TwirpServerFeature`][server-registration] or
[`TwirpBundle`][bundle] so routing errors are recognized as Twirp requests.
The `context` option changes the Java interface, so use the same setting on
both sides. See [request context and auth][context] for header forwarding.

### Client-only and server-only output

Use `server=false` to generate a service interface and client without a
resource. Use `client=false` to generate a service interface and resource
without a client. The interface is always emitted, and protoc's normal Java
message output is unaffected.

Setting both options to `false` is rejected. These flags do not generate a
resource alone or suppress duplicate shared message/interface classes across
modules; account for those classes when packaging separate client and server
artifacts.

## Proto support and limitations

The plugin generates methods from service descriptors and resolves message
types from the descriptors supplied by protoc, including imports. Field
serialization is handled by protoc's Java messages and protobuf `JsonFormat`,
not by the Twirp generator.

Multiple services, nested/imported messages, and well-known types can be used
in service definitions. The plugin advertises proto3 `optional` support.
Enums, repeated fields, maps, and `oneof` use the protobuf runtime's encoding.

Standard proto Java options are separate from the generator options above:
`java_package` selects the Java package, `java_multiple_files` controls whether
top-level message classes are separate, and `java_outer_classname` names the
outer message container when messages are nested. RPC URL paths always use the
proto package and original service/RPC names. Java keyword RPC names are
escaped, for example `Return` becomes `return_` in Java without changing `/Return`.

- **Editions:** `edition = "2023"` and other editions are unsupported because
  the plugin does not advertise `FEATURE_SUPPORTS_EDITIONS`. Use proto3 syntax.
- **Streaming:** client- and server-streaming methods are rejected. Streaming
  is excluded by the Twirp protocol.
- **Any over JSON:** register the packed types with a [`TypeRegistry`][any-json]
  on both the printer and parser. Binary protobuf needs no registry for transport.
- **JSON names:** the default printer preserves proto field names, including
  when a field defines `json_name`. See [JSON configuration][json] to change it.
- **Proto2 required fields:** missing required fields on decode are not
  correctly mapped to `malformed` (HTTP 400) and can produce HTTP 500. This is
  a [known runtime issue][runtime-limitations], not a protocol restriction.

[core]: https://github.com/dennyac/jaxrs-twirp/blob/main/jaxrs-twirp-core/README.md
[build]: https://github.com/dennyac/jaxrs-twirp/blob/main/README.md#building-from-source
[dependency]: https://github.com/dennyac/jaxrs-twirp/blob/main/README.md#1-add-the-runtime-dependency
[protobuf-maven-plugin]: https://www.xolstice.org/protobuf-maven-plugin/
[quickstart]: https://github.com/dennyac/jaxrs-twirp/blob/main/README.md#quickstart
[server-registration]: https://github.com/dennyac/jaxrs-twirp/blob/main/jaxrs-twirp-core/README.md#server-registration
[bundle]: https://github.com/dennyac/jaxrs-twirp/blob/main/dropwizard-twirp/README.md#twirpbundle
[context]: https://github.com/dennyac/jaxrs-twirp/blob/main/jaxrs-twirp-core/README.md#request-context-and-auth
[any-json]: https://github.com/dennyac/jaxrs-twirp/blob/main/jaxrs-twirp-core/README.md#any-over-json
[json]: https://github.com/dennyac/jaxrs-twirp/blob/main/jaxrs-twirp-core/README.md#json-configuration
[runtime-limitations]: https://github.com/dennyac/jaxrs-twirp/blob/main/jaxrs-twirp-core/README.md#runtime-limitations

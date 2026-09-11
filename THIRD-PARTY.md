# Third-party licenses

`jaxrs-twirp-core` and `dropwizard-twirp` do not bundle their dependencies.
Their main, source, and Javadoc jars carry the project's `META-INF/LICENSE`
and `META-INF/NOTICE`.

The protoc executable and runnable example bundle runtime dependencies.
Their `META-INF/THIRD-PARTY.txt` identifies those dependencies and their legal
files. Dependency jars' license and notice files are preserved under
`META-INF/third-party/<groupId as a path>/<artifactId>/<version>/`, without
merging unrelated files that share a name. Supplemental files in each module's
`src/shade` directory are included only in its binary jar.

The shared Protobuf license in `third-party/protobuf/LICENSE` is copied from
[Protobuf v32.1](https://raw.githubusercontent.com/protocolbuffers/protobuf/v32.1/LICENSE)
for `protobuf-java` and `protobuf-java-util` 4.32.1. Protobuf and JavaPoet source
copyright notices are retained separately. JavaPoet 1.13.0's license matches the
canonical Apache-2.0 text. The dependency indexes record the legal sources.

When changing shaded dependencies, review the actual bundled contents, update
the index and supplements, and inspect the resulting jars. Maven dependency
metadata and the presence of a license filename alone do not establish that
all required texts and notices are included.

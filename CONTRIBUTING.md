# Contributing

Bug reports and pull requests are welcome. Report security problems privately
as described in [SECURITY.md][security], not in a public issue.

## Build and test

You need JDK 17+ and Maven 3.9+. The build downloads `protoc` itself. From the
repository root:

```bash
mvn -B -ntp verify
```

This builds every module, runs the unit tests and the example's integration
tests (which start a real Jetty server), and runs the build checks below. To
build one module and the modules it depends on, use `-pl <module> -am`, for
example `mvn -pl jaxrs-twirp-core -am verify`. The example runs
`jaxrs-twirp-protoc` as a protoc plugin, which `-am` doesn't pick up, so list
both: `mvn -pl jaxrs-twirp-protoc,dropwizard-twirp-example -am verify`.

Coverage reports are written to `<module>/target/site/jacoco/index.html`.

Javadoc is built only by the release profile. CI also runs it, and broken
`{@link}` references or malformed HTML in doc comments fail the build:

```bash
mvn -B -ntp -Prelease -Dgpg.skip=true -DskipTests verify
```

## Build checks

`mvn verify` fails when:

- the JDK is older than 17 or Maven is older than 3.9;
- a Maven plugin has no pinned version;
- a POM declares the same dependency twice or adds a repository;
- a source file is missing the license header.

## License header

Every Java, proto, XML and YAML file needs this header at the top:

```java
// Copyright 2026 the jaxrs-twirp authors
// SPDX-License-Identifier: Apache-2.0
```

YAML files use `#` instead of `//`. XML files put the two lines, indented four
spaces, in a `<!--` ... `-->` block after the `<?xml ...?>` declaration.
`mvn license:format` adds a missing header. Markdown files, `LICENSE`, `NOTICE`
and files under `third-party/` and `src/shade/` are not checked.

## Pull requests

- Keep each pull request to one change, and say why it's needed.
- Add or update tests for behavior changes.
- Update the affected README, and add a line under "Unreleased" in
  [CHANGELOG.md][changelog] for changes users will notice.
- Make sure CI passes.

[security]: https://github.com/dennyac/jaxrs-twirp/blob/main/SECURITY.md
[changelog]: https://github.com/dennyac/jaxrs-twirp/blob/main/CHANGELOG.md

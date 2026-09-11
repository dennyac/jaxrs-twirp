# Publishing to Maven Central

This project publishes to **Maven Central** through the
[Central Portal](https://central.sonatype.com) using the
`central-publishing-maven-plugin`. The root `pom.xml` configures sources,
javadocs, GPG signing, and the Central publisher in its `release` profile.
Keep account credentials and private keys outside the repo.

Publish the parent POM and the three library modules together. The library POMs
inherit from the parent, so consumers need it on Central too.

| Artifact | Coordinates |
| --- | --- |
| Parent POM | `com.dennyac.twirp:jaxrs-twirp-parent` |
| `jaxrs-twirp-core` | `com.dennyac.twirp:jaxrs-twirp-core` |
| `dropwizard-twirp` | `com.dennyac.twirp:dropwizard-twirp` |
| `jaxrs-twirp-protoc` | `com.dennyac.twirp:jaxrs-twirp-protoc` |

`dropwizard-twirp-example` is a runnable demo, not a release artifact. The
deployment command below excludes it explicitly.

---

## One-time setup

### 1. Central Portal account + namespace verification

1. Sign in at <https://central.sonatype.com> (GitHub login works).
2. Register the namespace **`com.dennyac`** (Account → Namespaces → Add).
   Because this is a domain-based namespace, the portal asks you to prove you
   own `dennyac.com`: it shows a verification code, and you add a **DNS `TXT`
   record** containing that code to `dennyac.com`. Once DNS propagates, click
   *Verify*.

### 2. Central user token

Account → *Generate User Token*. You get a **username/password pair** (not your
login). These go into `settings.xml` under server id `central` (next step).

### 3. A GPG key (Central requires signed artifacts)

```bash
# Generate a key (pick RSA 4096, no expiry or a long one). Use your real email.
gpg --full-generate-key

# Find the key id (the long hex after "sec   rsa4096/")
gpg --list-secret-keys --keyid-format=long

# Publish the PUBLIC key so Central can verify signatures:
gpg --keyserver keyserver.ubuntu.com --send-keys <YOUR_KEY_ID>
# (good idea to also push to hkps://keys.openpgp.org)
```

Keep the **private key** and its **passphrase** safe — they're your release
identity.

### 4. `~/.m2/settings.xml`

Put credentials *outside* the repo. Minimal version:

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>CENTRAL_TOKEN_USERNAME</username>
      <password>CENTRAL_TOKEN_PASSWORD</password>
    </server>
  </servers>

  <profiles>
    <profile>
      <id>gpg</id>
      <properties>
        <gpg.passphrase>YOUR_GPG_PASSPHRASE</gpg.passphrase>
      </properties>
    </profile>
  </profiles>
  <activeProfiles>
    <activeProfile>gpg</activeProfile>
  </activeProfiles>
</settings>
```

> If GPG can't prompt during the build (e.g. headless), add
> `<gpg.pinentryMode>loopback</gpg.pinentryMode>` next to `gpg.passphrase`.

---

## Cutting a release

The repo stays on a `-SNAPSHOT` version during development. Commit the release
versions before building: the tag must identify the committed source used to
produce the published artifacts, not the preceding SNAPSHOT commit.

Run these steps from the repo root in the same shell, substituting the release
and next-development versions as needed. These commands assume direct pushes
to `main` are allowed. Stop on any failure; never force-push or move a release
tag.

### 1. Start from a clean, current `main`

Use a dedicated checkout with no uncommitted files or unpublished commits. Both
`test` commands below must succeed before proceeding.

```bash
test -z "$(git status --porcelain)" &&
git fetch origin &&
git switch main &&
git merge --ff-only origin/main &&
test "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" &&
mvn clean verify
```

### 2. Commit and push the release versions

```bash
mvn versions:set -DnewVersion=0.1.0 -DgenerateBackupPoms=false
git diff -- pom.xml */pom.xml
```

Review all five POMs: the root version and every module's parent version,
including the example's, must be `0.1.0`. Then commit only those POM changes.
Keep `release_commit` for the later tag.

```bash
git add pom.xml */pom.xml &&
git commit -m "Release 0.1.0" &&
release_commit=$(git rev-parse HEAD) &&
git push origin main
```

### 3. Build and upload that committed source

Credentials come from the `central` server in `settings.xml`. `-pl` selects the
three libraries; `-am` includes their required parent POM, without the example.
The example's `maven.deploy.skip` controls Maven's deploy plugin, not the
Central publisher.

```bash
test "$(git rev-parse HEAD)" = "$release_commit" &&
test -z "$(git status --porcelain)" &&
mvn -Prelease -pl jaxrs-twirp-core,dropwizard-twirp,jaxrs-twirp-protoc -am clean deploy &&
test -z "$(git status --porcelain)"
```

### 4. Publish, then tag the release commit

`autoPublish=false` leaves the deployment awaiting manual publication. At
<https://central.sonatype.com/publishing/deployments>, wait for validation and
confirm the deployment contains the parent POM and three libraries at `0.1.0`,
not the example. Click **Publish** and wait for the deployment to be published
before tagging.

```bash
test "$(git rev-parse HEAD)" = "$release_commit" &&
test -z "$(git status --porcelain)" &&
git tag -a v0.1.0 -m "jaxrs-twirp 0.1.0" "$release_commit" &&
git push origin refs/tags/v0.1.0
```

### 5. Commit the next development version separately

Only proceed after publication and the tag push succeed. Review the version
changes before committing; this commit must not be part of the release tag.

```bash
mvn versions:set -DnewVersion=0.2.0-SNAPSHOT -DgenerateBackupPoms=false
git diff -- pom.xml */pom.xml
git add pom.xml */pom.xml
git commit -m "Start 0.2.0-SNAPSHOT"
git push origin main
```

Publishing and search indexing can take time. Look for the release at
<https://central.sonatype.com> and
<https://repo1.maven.org/maven2/com/dennyac/twirp/>.

---

## Verifying the wiring without releasing

You can inspect release packaging before account/key setup by skipping signing:

```bash
mvn -Prelease -DskipTests -Dgpg.skip=true clean verify
# each library module's target/ should contain -sources.jar and -javadoc.jar
```

This checks packaging only; it does not sign, upload, or validate a deployment
with Central.

---

## Notes

- **Plugin versions** for the release tooling are pinned as properties in the
  root POM (`maven-source-plugin`, `maven-javadoc-plugin`, `maven-gpg-plugin`,
  `central-publishing-maven-plugin`); bump them there as new versions ship.
- **You cannot deploy a `-SNAPSHOT`** to a Central *release*. SNAPSHOTs can be
  pushed to the Central **snapshot** repository if you want pre-release testing;
  that's optional and not configured here.
- Keep `settings.xml`, your GPG private key, and the token **out of git**.

# Publishing to Maven Central

This project publishes to **Maven Central** through the
[Central Portal](https://central.sonatype.com) using the
`central-publishing-maven-plugin`. Everything Maven-side is already wired into
the root `pom.xml` behind a `release` profile (sources jar, javadoc jar, GPG
signing, and the Central publisher). What's left is **account and key setup**,
which is specific to you and must never live in the repo.

The published artifacts are the three library modules:

| Module | Coordinates |
| --- | --- |
| `jaxrs-twirp-core` | `com.dennyac.twirp:jaxrs-twirp-core` |
| `dropwizard-twirp` | `com.dennyac.twirp:dropwizard-twirp` |
| `jaxrs-twirp-protoc` | `com.dennyac.twirp:jaxrs-twirp-protoc` |

`dropwizard-twirp-example` is a runnable demo and is **not** published
(`maven.deploy.skip=true` in its POM).

---

## One-time setup

### 1. Central Portal account + namespace verification

1. Sign in at <https://central.sonatype.com> (GitHub login works).
2. Register the namespace **`com.dennyac`** (Account → Namespaces → Add).
   Because this is a domain-based namespace, the portal asks you to prove you
   own `dennyac.com`: it shows a verification code, and you add a **DNS `TXT`
   record** containing that code to `dennyac.com`. Once DNS propagates, click
   *Verify*. (You own the domain, so this is the right path — no `io.github.*`
   fallback needed.)

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

The repo stays on a `-SNAPSHOT` version during development. A release is just:
bump → deploy → publish → tag → bump back.

```bash
# 0. Make sure the tree is clean and tests pass.
mvn clean verify

# 1. Drop the -SNAPSHOT for the release (updates every module POM).
mvn versions:set -DnewVersion=0.1.0 -DgenerateBackupPoms=false

# 2. Build signed artifacts and upload them to the Central Portal.
#    The `release` profile attaches sources+javadoc, signs with GPG, and
#    invokes the Central publisher. Credentials come from settings.xml.
mvn -Prelease clean deploy

# 3. Finish in the portal:
#    autoPublish is false, so the upload lands as a "validated" deployment you
#    review and click **Publish** on at https://central.sonatype.com/publishing.
#    (Set <autoPublish>true</autoPublish> in the root POM's release profile to
#    skip this manual step on future releases.)

# 4. Tag the release.
git tag -a v0.1.0 -m "jaxrs-twirp 0.1.0"
git push origin v0.1.0

# 5. Open the next development iteration.
mvn versions:set -DnewVersion=0.2.0-SNAPSHOT -DgenerateBackupPoms=false
git commit -am "Start 0.2.0-SNAPSHOT"
```

After *Publish*, artifacts typically appear on Central within ~15–30 minutes and
are searchable at <https://central.sonatype.com> and via
<https://repo1.maven.org/maven2/com/dennyac/twirp/>.

---

## Verifying the wiring without releasing

You can prove the `release` profile produces the right artifacts **before** any
account/key setup by skipping the signing step:

```bash
mvn -Prelease -DskipTests -Dgpg.skip=true clean verify
# each library module's target/ should contain -sources.jar and -javadoc.jar
```

(This exact command is part of the project's pre-release checklist and passes
today.)

---

## Notes

- **Plugin versions** for the release tooling are pinned as properties in the
  root POM (`maven-source-plugin`, `maven-javadoc-plugin`, `maven-gpg-plugin`,
  `central-publishing-maven-plugin`); bump them there as new versions ship.
- **You cannot deploy a `-SNAPSHOT`** to a Central *release*. SNAPSHOTs can be
  pushed to the Central **snapshot** repository if you want pre-release testing;
  that's optional and not configured here.
- Keep `settings.xml`, your GPG private key, and the token **out of git**.

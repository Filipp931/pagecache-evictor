# Releasing

Two independent channels:

1. **GitHub Release** (CLI: native binary + fat jar) — fully automated by tag.
2. **Maven Central** (`pagecache-evictor` core + `pagecache-evictor-spring-boot-starter`)
   — needs one-time credential setup below.

## 1. GitHub Release

```bash
git tag v0.1.0
git push origin v0.1.0
```

`release.yml` derives the version from the tag (`v0.1.0` → `-Pversion=0.1.0`),
builds and smoke-tests the GraalVM binary and the shadow jar, and attaches
`pagecache-linux-amd64` and `pagecache.jar` to an auto-created GitHub Release.
Uses the built-in `GITHUB_TOKEN`; nothing to configure. The `0.1.0-SNAPSHOT`
in `build.gradle.kts` is only a fallback for builds without `-Pversion`.

## 2. Maven Central — one-time setup

The build applies `com.vanniktech.maven.publish` with full POMs for `core`
and `starter`. Credentials are deliberately not in the repo.

### 2.1 Central Portal account

1. Sign in at <https://central.sonatype.com> with your GitHub account.
2. Register the namespace `io.github.filipp931` (automatic verification for
   `io.github.*`).
3. Generate a user token (Account → Generate User Token) — that pair is
   `mavenCentralUsername` / `mavenCentralPassword` below.

### 2.2 GPG signing key

```bash
gpg --full-generate-key            # RSA 4096
gpg --list-secret-keys --keyid-format long
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
gpg --export-secret-keys --armor <KEY_ID> > private.asc
```

### 2.3 Publish locally

`~/.gradle/gradle.properties`:

```properties
mavenCentralUsername=<token username>
mavenCentralPassword=<token password>
signingInMemoryKeyPassword=<key passphrase>
```

Passing the multi-line key via the environment is easier than escaping it in
a properties file:

```bash
export ORG_GRADLE_PROJECT_signingInMemoryKey="$(cat private.asc)"
./gradlew -Pversion=0.1.0 publishAndReleaseToMavenCentral
```

(`publishToMavenCentral` uploads without auto-releasing — useful for
inspecting the very first deployment in the Portal UI.)

### 2.4 Publishing from GitHub Actions (optional, later)

Repository secrets:

| Secret | Value |
|--------|-------|
| `ORG_GRADLE_PROJECT_mavenCentralUsername` | token username |
| `ORG_GRADLE_PROJECT_mavenCentralPassword` | token password |
| `ORG_GRADLE_PROJECT_signingInMemoryKey` | armored private key |
| `ORG_GRADLE_PROJECT_signingInMemoryKeyPassword` | key passphrase |

and a step in `release.yml`:

```yaml
      - name: Publish to Maven Central
        run: ./gradlew -Pversion="$RELEASE_VERSION" publishAndReleaseToMavenCentral
        env:
          ORG_GRADLE_PROJECT_mavenCentralUsername: ${{ secrets.ORG_GRADLE_PROJECT_mavenCentralUsername }}
          ORG_GRADLE_PROJECT_mavenCentralPassword: ${{ secrets.ORG_GRADLE_PROJECT_mavenCentralPassword }}
          ORG_GRADLE_PROJECT_signingInMemoryKey: ${{ secrets.ORG_GRADLE_PROJECT_signingInMemoryKey }}
          ORG_GRADLE_PROJECT_signingInMemoryKeyPassword: ${{ secrets.ORG_GRADLE_PROJECT_signingInMemoryKeyPassword }}
```

## 3. Release checklist

1. README: bump the dependency snippets to `X.Y.Z`.
2. Commit, tag `vX.Y.Z`, push with the tag — the tag is the single source of
   the released version.
3. Publish core + starter to Maven Central with the same `-Pversion=X.Y.Z`.
4. Optionally bump the `-SNAPSHOT` fallback in `build.gradle.kts`.

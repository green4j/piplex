# GitHub Actions

Two workflows, both plain single-checkout builds. `piplex` depends on `discas` as a published
artifact like any other dependency, so nothing about that project has to be present in this one's CI.

## build.yml

Runs on push and pull request against `main`, and on demand. Two jobs.

`gradle-build` sweeps JDK **21 and 25** and runs `clean build`. Java 21 is the floor, Jenkins 2.568.3
is the security-fixed baseline, and that LTS supports both JDKs in the sweep.

Every dependency is a fixed version, named in `gradle.properties` and never a range, so what a build
resolves is what the file says. `jenkinsVersion` names the core baseline; its major/minor line selects
the BOM family, and `jenkinsBomVersion` fixes that BOM's contents. The plugin module then names no
individual plugin versions.

The full build includes the Jenkins unit, CPS/restart and Docker/discas integration suites on that
baseline. When a later LTS is added to the compatibility claim, its matrix cell passes the core and
matching BOM versions together; while baseline and newest LTS are the same release, a second job
would only repeat `gradle-build`.

## release.yml

Runs on a `v*` tag push. It calls `build.yml` first, so a tag is released only if the same checks that
guard `main` pass on the commit it points at -- a tag can point at any commit, and without this a
release would be the one build nothing has to pass.

Then **two** jobs, and the split is the point of them. `build` runs Gradle on JDK 21 and can do
nothing but read the repository; it hands the `.hpi`, the operator `.zip` and both `.sha256` files on
as one workflow artifact. `publish` runs no build of ours at all -- it takes those four files, checks
both checksums again, signs provenance for both payloads and creates the release. The credentials
live only in the second job:
running a build under them means every Gradle plugin and every dependency it resolves runs with a
token that can write releases and sign attestations, and none of that code needs either.

The checksums make both payloads checkable rather than asserted:
`sha256sum -c piplex-<version>.hpi.sha256` and
`sha256sum -c piplex-operator-<version>.zip.sha256`. Their attestations say which workflow at which
commit produced them, signed by GitHub:

```text
gh attestation verify piplex-<version>.hpi --repo green4j/piplex
gh attestation verify piplex-operator-<version>.zip --repo green4j/piplex
```

A re-run whose assets are already published stops at the upload rather than replacing them: bytes an
operator has checksummed are not something to overwrite silently. A run that failed after creating the
release but before uploading everything is finished by hand, with
`gh release upload <tag> <asset> --clobber`.

Nothing is published to a Maven repository. The `.hpi` carries the core, the discas adapter and the
discas client installed on a controller. The operator archive carries the `piplex-init` executable
jar, POSIX and Windows launchers, the five job templates and the deployment chapter, so the
operational half cannot drift from the plugin release. The build unpacks that archive and runs the
POSIX launcher before publishing it. No project secret publishes either payload: GitHub's own token,
with `contents: write` to make the release and `id-token: write` to sign the attestations, is all it
takes. The workflow itself grants nothing (`permissions: {}`); each job says what it may do.

Before building it checks that `version.txt` is non-empty, that the tag matches it exactly
(`v<version>`), and that the version is not a `-SNAPSHOT` -- the jpi plugin stamps a snapshot build's
manifest as `private-<timestamp>-<user>`, which is not something to hand a controller.

What a release buys is knowing what is installed: every controller gets the same bytes, and
**Manage Jenkins > Plugins** shows the released version rather than whoever's laptop built it.

### Releasing

```
echo 0.1.0 > version.txt
git commit -am "Release 0.1.0"
git tag v0.1.0
git push && git push --tags
```

then bump back to `0.1.1-SNAPSHOT`.

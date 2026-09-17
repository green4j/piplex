# GitHub Actions

Two workflows, both plain single-checkout builds. `piplex` depends on `discas` as a published
artifact like any other dependency, so nothing about that project has to be present in this one's CI.

## build.yml

Runs on push and pull request against `main`, and on demand. Two jobs.

`gradle-build` sweeps JDK **21 and 25** and runs `clean build`. Java 21 is the floor: `piplex` is
released on it, unlike `newa` and `discas`, which are on 11. It is also Jenkins' own floor from LTS
2.555.1 on, which is the baseline here -- so the plugin asks a controller for nothing the core does
not already require, and both JDKs in the sweep are ones that baseline supports.

Every dependency is a fixed version, named in `gradle.properties` and never a range, so what a build
resolves is what the file says. The plugin module has none of its own to name: one BOM, the one the
`jenkinsLines` table pairs with the baseline, fixes every Jenkins plugin version at once.

`jenkins-lts` runs the plugin's tests alone against the **newest Jenkins LTS**, on both JDKs. The
baseline in `gradle.properties` is the oldest controller piplex runs on and is what the `.hpi` is
stamped with; this job is the other half of the claim -- that it still works on the newest one -- and
is the half nothing else checks. Passing it is not a reason to raise the baseline, which only ever
narrows the set of controllers that can install the plugin.

A cell names a Jenkins version and a JDK, and nothing else. The plugin BOM that goes with the version
comes from the `jenkinsLines` table in `build.gradle`, so the two cannot be given values from
different lines, and a version the table does not know fails the build saying so. Adding a line is one
entry there and one value here.

## release.yml

Runs on a `v*` tag push. It calls `build.yml` first, so a tag is released only if the same checks that
guard `main` pass on the commit it points at -- a tag can point at any commit, and without this a
release would be the one build nothing has to pass.

Then **two** jobs, and the split is the point of them. `build` runs Gradle on JDK 21 and can do
nothing but read the repository; it hands the `.hpi` and its `.sha256` on as a workflow artifact.
`publish` runs no build of ours at all -- it takes those two files, checks the checksum again, signs
the provenance attestation and creates the release. The credentials live only in the second job:
running a build under them means every Gradle plugin and every dependency it resolves runs with a
token that can write releases and sign attestations, and none of that code needs either.

The checksum is what makes "every controller gets the same bytes" checkable rather than asserted:
`sha256sum -c piplex-<version>.hpi.sha256` after downloading the pair. The attestation says which
workflow at which commit produced them, signed by GitHub:
`gh attestation verify piplex-<version>.hpi --repo green4j/piplex`.

A re-run whose assets are already published stops at the upload rather than replacing them: bytes an
operator has checksummed are not something to overwrite silently. A run that failed after creating the
release but before uploading everything is finished by hand, with
`gh release upload <tag> <asset> --clobber`.

Nothing is published to a Maven repository. The `.hpi` is the only artifact anybody installs, and it
already carries the core, the discas adapter and the discas client inside it -- so a release is one
file, and no secrets of ours: GitHub's own token, with `contents: write` to make the release and
`id-token: write` to sign the attestation, is all it takes. The workflow itself grants nothing
(`permissions: {}`); each job says what it may do.

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

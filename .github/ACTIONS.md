# GitHub Actions

Two workflows, both plain single-checkout builds. `piplex` depends on `discas` as a published
artifact like any other dependency, so nothing about that project has to be present in this one's CI.

## build.yml

Runs on push and pull request against `main`, and on demand. Two jobs.

`gradle-build` sweeps JDK **17, 21 and 25** and runs `clean build`. Java 17 is the floor: `piplex` is
released on it, unlike `newa` and `discas`, which are on 11.

`jenkins-lts` runs the plugin's tests alone against the **newest Jenkins LTS**, on JDK 17. The baseline
in `gradle.properties` is the oldest controller piplex runs on and is what the `.hpi` is stamped with;
this job is the other half of the claim -- that it still works on the newest one -- and is the half
nothing else checks. Passing it is not a reason to raise the baseline, which only ever narrows the set
of controllers that can install the plugin.

A cell names a Jenkins version and nothing else. The plugin BOM that goes with it comes from the
`jenkinsLines` table in `build.gradle`, so the two cannot be given values from different lines, and a
version the table does not know fails the build saying so. Adding a line is one entry there and one
value here.

## release.yml

Runs on `workflow_dispatch` with a `snapshot` / `release` choice, or on a `v*` tag push (which always
means `release`). Publishes on JDK 17.

Before publishing it validates that `version.txt` is non-empty, that a tag push matches it exactly
(`v<version>`), that the Sonatype secrets are present, and that the version's `-SNAPSHOT` suffix agrees
with the chosen mode. A release additionally requires the signing secrets.

### Releasing

```
echo 0.1.0 > version.txt
git commit -am "Release 0.1.0"
git tag v0.1.0
git push && git push --tags
```

then bump back to `0.1.1-SNAPSHOT`.

### Secrets

| Secret | For |
|---|---|
| `SONATYPE_USERNAME` | Sonatype Central Portal token username |
| `SONATYPE_PASSWORD` | Sonatype Central Portal token password |
| `SIGNING_GPG_SECRET_KEY` | ascii-armored secret key; releases only |
| `SIGNING_GPG_PASSWORD` | its passphrase; releases only |

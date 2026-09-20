## Quick start

This guide runs one nightly job on two Jenkins controllers. Piplex decides which of them runs it,
the other waits to take over, and a consumer waits for the producer's milestone.

You need:

- two Jenkins controllers, version 2.568.3 or later, running Java 21;
- one three-node discas cluster reachable from both controllers;
- the same pipeline available on both controllers.

The cluster may use its trusted-environment profile for this first run. Before production, turn on
TLS, authentication and ACLs with [the security guide](07-discas.md).

### 1. Install the plugin

Download `piplex-<version>.hpi`, `piplex-operator-<version>.zip` and their checksums from a
[release](https://github.com/green4j/piplex/releases).

```text
# Linux
sha256sum -c piplex-<version>.hpi.sha256

# macOS
shasum -a 256 -c piplex-<version>.hpi.sha256
```

Optionally verify provenance with GitHub CLI:

```text
gh attestation verify piplex-<version>.hpi --repo green4j/piplex
```

On each controller, open **Manage Jenkins > Plugins > Advanced settings > Deploy Plugin**, upload the
`.hpi`, and restart.

### 2. Describe the estate

Four things have to name the same keys: the cluster's ACL, each controller's settings, the operator
jobs, and the block in the guarded pipeline. Written by hand they are four places to keep in step,
and they stop being in step silently -- a grant fails a write nobody makes until the night somebody
needs it. So they are written together, from one description:

```text
unzip piplex-operator-<version>.zip
cd piplex-operator-<version>
./piplex-init
```

On Windows, run `piplex-init.cmd`. Both launchers use `JAVA_HOME` when it is set and otherwise find
Java on `PATH`; `java -jar piplex-init.jar` remains equivalent.

It asks. Seven sections, in any order, with a number to jump at the one you want to change; `w`
writes everything out, `q` leaves without writing. What is wrong with the estate is printed under the
menu every round rather than held back until the end.

For this guide, answer:

| Section | Answer |
|---|---|
| Environment | `prod` |
| Cluster | your `nodeId=host:port` list, e.g. `n1=10.0.0.11:7101,n2=10.0.0.12:7101,n3=10.0.0.13:7101` |
| Transport | a trusted network |
| Controllers | `euc1-blue` and `euc1-green`, taking the offered client ids |
| Operator | no identity of its own |
| Work | key `eod`, switch `eod-switch`, piplex decides where it runs, designation `eod-owner`, milestone `data/euc1`, handover wait `4h` |
| Grants | a grant per environment |

The environment is a segment of every key, so both controllers have to agree on it: two controllers
competing for one piece of work must look at one key. Changing it later moves every key, which is why
it is the first question -- see
[environments and cluster ids](07-discas.md#environments-and-cluster-ids).

It writes a directory:

```text
piplex/
  estate.properties               the answers, read back by the next run
  RUN.md                          what to do with the rest of this
  acl/piplex.conf                 for the cluster
  controllers/euc1-blue/          jenkins.yaml, and five operator jobs
  controllers/euc1-green/         the same, with this controller's own names
  pipelines/eod.groovy            the block the guarded pipeline needs
```

The `piplex/` directory belongs to `piplex-init`. Regeneration prepares a complete replacement and
then swaps it in, so controllers and pipelines removed from `estate.properties` do not survive as
stale files. Keep the directory in a repository, not a repository inside the directory, and keep
hand-written files beside it rather than under it.

### 3. Install what it wrote

`RUN.md` is the detailed version. For this guide:

- point every discas node at `acl/piplex.conf` (`--client-acl-file`) and `POST /reload`;
- on each controller, apply `controllers/<ownerId>/jenkins.yaml` through Configuration as Code, or
  copy the same values into **Manage Jenkins > System > piplex**;
- create the five jobs in `controllers/<ownerId>/jobs/` as pipeline jobs, in one folder.

The settings page does not test connectivity. Run one build that reaches the store, so a wrong port
shows up now rather than at two in the morning.

### 4. Guard the job

Use the same Jenkinsfile on each controller, with `pipelines/eod.groovy` pasted in as its `options`
and `post`:

```groovy
pipeline {
    agent none

    parameters {
        string(name: 'BUSINESS_DATE', defaultValue: '2026-09-14',
               description: 'ISO-8601 business date')
    }

    // Generated: options { piplexExclusive(...) } from pipelines/eod.groovy
    options {
        piplexExclusive(
            // The work. One build at a time across every controller
            key: 'eod',
            // Who runs it. Written by the handover job, read here
            designatedBy: 'eod-owner',
            // The switch. Off revokes runs in flight and skips new ones
            enabledBy: 'eod-switch',
            // What this round produces. Replace with your own convention
            generation: params.BUSINESS_DATE,
            // Already produced? Then this build ends NOT_BUILT
            completedWhen: 'data/euc1',
            // How long a candidate parks, holding no executor
            handoverWait: '4h'
        )
    }

    triggers { cron('H 2 * * *') }

    stages {
        stage('EOD') {
            agent any
            steps {
                sh './eod.sh' // Your job; it needs no piplex knowledge
            }
        }
    }

    // Generated: the publication, which must happen while the lease is still held
    post {
        success {
            piplexPublish key: 'data/euc1',
                          generation: params.BUSINESS_DATE
        }
    }
}
```

Generations are compared lexicographically, so ISO-8601 dates are safe and numeric counters must be
zero-padded. Replace the fixed default date with whatever convention your estate already uses.

The declarative `options` wrapper covers the whole build, `post` included, so the milestone is
published while the lease is still held. In a scripted pipeline, put `piplexPublish` inside the
`piplexExclusive { ... }` block instead.

### 5. Name the first owner

Nothing runs until somebody is designated. Run the generated `handover` job on either controller,
choosing work `eod` and owner `euc1-blue`.

At the next trigger:

- blue is admitted and runs;
- green parks without an executor for up to `handoverWait`;
- a second build of the same work cannot enter while the lease is held;
- success publishes `data/euc1` at the requested generation.

### 6. Exercise a handover

Run `handover` again, choosing `euc1-green`, while blue is running or green is parked.

Blue is revoked, and Jenkins cancels its guarded body. Green wakes, waits for the lease to be
released or lapse, then takes over. Without a parked candidate, the next scheduled green build runs.

The job's Confirm stage then reads every guard the work names and says whether it can actually run
where it was just sent. A yellow build means the designation landed and something still stops it.

### 7. Exercise a stop and a drain

Two different things, which is why they are two jobs.

Run `stop-work` with `stop`: runs in flight are revoked everywhere and nobody takes over. `resume`
puts it back.

Run `drain-controller` with `drain` **on** one controller: it takes that controller out and waits for
work already running there to stop. It covers every switch the estate knows, because the question is
whether the machine is safe to touch. `restore` puts it back.

Whichever you ran, ask why a build did not run -- run `inspect` and choose the work. It reads every
guard from the estate, so there is nothing to type and nothing to leave out.

### 8. Add a consumer

Any pipeline on any controller can wait for the producer:

```groovy
stage('Wait for EOD data') {
    steps {
        // Waits for the producer. Goal: start only after the date is produced.
        // Effect: holds no executor; fails if the date is not produced within 90 minutes
        piplexAwait key: 'data/euc1',                 // Milestone to watch
                    generation: params.BUSINESS_DATE, // Date needed, or any later one
                    timeout: '90m'                    // Maximum wait
    }
}
```

The wait holds no executor. It succeeds when the stored generation is at least the requested one and
fails on timeout unless `skipOnTimeout: true`.

### Where failover tooling already decides

If something outside piplex already marks the active site, say so in the work section instead of
naming a designation: piplex then reads that key and follows it. The two cannot be combined -- they
answer the same question -- and `piplex-init` refuses an estate that names both. See
[election, designation and handover](02-exclusive-run.md).

### Before production

[Deployment](09-deployment.md) is this guide again in the order to follow when it matters, with the
decisions named. Before that, the five this guide skipped:

- Turn on token authentication with TLS, or mTLS, and answer the transport and grant sections
  accordingly: [ACLs](07-discas.md#acls).
- Pass the [fencing token](05-jenkins.md#fencing-token) to the protected resource, or make the work
  idempotent.
- Decide whether [`handoverWait`](02-exclusive-run.md#parking-and-handover) should cover the interval
  until the next schedule.
- Aggregate [`piplex:` log lines](06-operations.md#event-vocabulary) by `key` and `generation`.
- Rehearse restart and store-loss procedures from [Lifecycle](08-lifecycle.md).

Next: [1. The model](01-model.md) · [Documentation index](README.md)

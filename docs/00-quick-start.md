## Quick start

This guide runs one nightly job on two Jenkins controllers. One controller is designated, the other
waits to take over, and a consumer waits for the producer's milestone.

You need:

- two Jenkins controllers, version 2.555.1 or later, running Java 21;
- one three-node discas cluster reachable from both controllers;
- the same pipeline available on both controllers.

For production TLS, authentication and ACLs, use [the security guide](07-discas.md) before going live.

### 1. Install the plugin

Download `piplex-<version>.hpi` and its checksum from a
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

### 2. Configure both controllers

Open **Manage Jenkins > System > piplex**.

| Field | Blue controller | Green controller |
|---|---|---|
| Owner id | `euc1-blue` | `euc1-green` |
| Client id | `piplex-euc1-blue` | `piplex-euc1-green` |
| discas nodes | the same `nodeId=host:port` list | the same list |

Example node list:

```text
n1=10.0.0.11:7101,n2=10.0.0.12:7101,n3=10.0.0.13:7101
```

For this first run, the cluster may use its trusted-environment profile. In production, give every
controller its own authenticated client id and configure TLS as described in
[discas and security](07-discas.md).

### 3. Name the initial owner

On either controller, open **Manage Jenkins > Script Console**:

```groovy
import hudson.model.TaskListener
import io.github.green4j.piplex.jenkins.PiplexConfiguration

def piplex = PiplexConfiguration.get().piplexFor(TaskListener.NULL)
piplex.designations()
      .designate('eod-owner', 'euc1-blue', 'initial owner')
      .toCompletableFuture().join()
```

The designation is shared state and survives controller restarts.

### 4. Create the job on both controllers

Use the same Jenkinsfile on each controller:

```groovy
pipeline {
    agent none

    parameters {
        string(name: 'BUSINESS_DATE', defaultValue: '2026-09-14',
               description: 'ISO-8601 business date')
    }

    options {
        // Guards the whole build, including post
        piplexExclusive(
            // Name of the protected work. Goal: never run it twice at once.
            // Effect: a second build on any controller parks or ends NOT_BUILT
            key: 'eod',
            // Designation that names the controller to run. Goal: choose where the work runs.
            // Effect: other controllers park or skip; redesignating aborts the running build
            designatedBy: 'eod-owner',
            // Operational switch. Goal: stop the work without editing jobs.
            // Effect: disable('eod-switch') aborts running builds and skips new ones
            enabledBy: 'eod-switch',
            // Unit of work this build produces, here a business date.
            // Effect: compared with the completedWhen milestone
            generation: params.BUSINESS_DATE,
            // Milestone the producer publishes. Goal: never redo a finished date.
            // Effect: the build ends NOT_BUILT if the milestone has this date or later
            completedWhen: 'data/euc1',
            // Ownership term, renewed while the build runs. Goal: survive a crashed controller.
            // Effect: a standby takes over at most this long after the holder dies
            lease: '60s',
            // Time a build that cannot run yet waits without an executor. Goal: take over
            // without waiting for the next cron. Effect: after a handover it starts at once
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

    post {
        success {
            // Records that the date is produced. Goal: announce the result.
            // Effect: waiting consumers continue, later builds for the date skip.
            // Runs before the lease is released
            piplexPublish key: 'data/euc1',                // Milestone to raise
                          generation: params.BUSINESS_DATE // Date just produced
        }
    }
}
```

Replace the fixed default date with the parameter or shared-library convention used by your estate.
Generations are compared lexicographically, so ISO-8601 dates are safe and numeric counters must be
zero-padded.

The declarative `options` wrapper covers the whole build, including `post`. The success milestone is
therefore published while the lease is still held. In a scripted pipeline, put `piplexPublish`
inside the `piplexExclusive { ... }` block when using `completedWhen`.

At trigger time:

- blue is admitted and runs;
- green parks without an executor for up to `handoverWait`;
- a second build of the same work cannot enter while the lease is held;
- success publishes `data/euc1` at the requested generation.

### 5. Exercise a handover

While blue is running or green is parked:

```groovy
piplex.designations()
      .designate('eod-owner', 'euc1-green', 'handover test')
      .toCompletableFuture().join()
```

Blue is revoked and Jenkins cancels its guarded body. Green wakes, waits for the lease to be released
or lapse, then takes over. Without a parked candidate, the next scheduled green build runs.

### 6. Exercise a drain

Disable the work everywhere:

```groovy
piplex.switches().disable('eod-switch', 'maintenance test').toCompletableFuture().join()
```

Runs in flight are revoked and no controller takes over. Re-enable it with:

```groovy
piplex.switches().enable('eod-switch').toCompletableFuture().join()
```

To drain only blue:

```groovy
piplex.switches()
      .disable('eod-switch/@euc1-blue', 'patching blue')
      .toCompletableFuture().join()
```

### 7. Add a consumer

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

### Before production

- Turn on token authentication with TLS, or mTLS, and configure ACLs.
- Give every controller distinct `ownerId` and `clientId` values.
- Pass the fencing token to the protected resource, or make the work idempotent.
- Decide whether `handoverWait` should cover the interval until the next schedule.
- Aggregate `piplex:` log lines by `key` and `generation`.
- Rehearse restart and store-loss procedures from [Lifecycle](08-lifecycle.md).

Next: [1. The model](01-model.md) · [Documentation index](README.md)

## piplex

Cross-instance orchestration primitives for Jenkins pipelines.

Use piplex when several Jenkins controllers may run the same work, or when a pipeline on one
controller must wait for work completed on another. Jenkins remains the source of truth for builds,
logs, artifacts and schedules; piplex stores only the state the controllers must agree on.

If there is one controller and no cross-controller dependency, piplex is unnecessary.

### What it provides

- **Exclusive runs** elect a lease holder or admit only the designated owner. Admission is held for
  the life of the work: changing the designation or disabling the work revokes a run in flight.
- **Milestones** publish the generation a producer completed. Publishing is monotonic and idempotent;
  waiting compares current state, so it survives missed changes and restarts.
- **Switches** enable, disable or drain work through shared state instead of a configuration deploy.
- **Active keys** follow a value another system writes, such as the active data centre: work runs
  only where the value matches and moves when it changes.
- **Environments** let one cluster serve several sets of orchestrations. Every key is under
  `piplex/<environment>/`, so production and uat share no milestone, switch, designation or lease.

### Safety boundary

The store permits at most one live lease per key and gives every acquisition a strictly increasing
fencing token. A former holder may still act between lease expiry and noticing the loss. Preventing
stale writes therefore requires the protected resource to reject lower fencing tokens; otherwise the
work must be idempotent.

See [the full model](docs/01-model.md#guarantees) for the exact guarantees.

### Jenkins example

```groovy
pipeline {
    agent none
    options {
        // Guards the whole build, including post
        piplexExclusive(
            // Name of the protected work. Goal: never run it twice at once.
            // Effect: a second build on any controller parks or ends NOT_BUILT
            key: 'eod',
            // Key the failover tooling writes, e.g. /dc/active = euc1-blue. Goal: run on the
            // active controller. Effect: others park or skip; a new value aborts the running build
            activeWhenKey: '/dc/active',
            // Value that admits this controller; each controller sets its own
            activeWhenValue: 'euc1-blue',
            // Operational switch. Goal: stop the work without editing jobs.
            // Effect: disable('eod-switch') aborts running builds and skips new ones
            enabledBy: 'eod-switch',
            // Unit of work this build produces, here a business date.
            // Effect: compared with the completedWhen milestone
            generation: env.BUSINESS_DATE,
            // Milestone the producer publishes. Goal: never redo a finished date.
            // Effect: the build ends NOT_BUILT if the milestone has this date or later
            completedWhen: 'data/euc1',
            // Time an inactive build waits without an executor. Goal: if failover switches
            // /dc/active here, run tonight, not at the next cron. Ends early once the milestone is published
            handoverWait: '4h'
        )
    }
    triggers { cron('H 2 * * *') }
    stages {
        stage('EOD') {
            agent any
            steps { sh './eod.sh' } // Your job; it needs no piplex knowledge
        }
    }
    post {
        success {
            // Records that the date is produced. Goal: announce the result.
            // Effect: waiting consumers continue, later builds for the date skip.
            // Runs before the lease is released
            piplexPublish key: 'data/euc1',             // Milestone to raise
                          generation: env.BUSINESS_DATE // Date just produced
        }
    }
}
```

Put the same job and cron on every controller, differing only in `activeWhenValue`. `BUSINESS_DATE`
is supplied by the pipeline. To choose the owner inside piplex instead, replace both active-key
parameters with `designatedBy`. The declarative `options` wrapper includes `post`, so the milestone
is published before the lease is released.

Consumers wait without occupying an executor:

```groovy
// Waits for the producer. Goal: start only after the date is produced.
// Effect: holds no executor; fails if the date is not produced within 90 minutes
piplexAwait key: 'data/euc1',              // Milestone to watch
            generation: env.BUSINESS_DATE, // Date needed, or any later one
            timeout: '90m'                 // Maximum wait
```

Inside `piplexExclusive`, `PIPLEX_FENCING_TOKEN` and `piplexToken()` expose the current fencing token.
Using it is optional; work that ignores it should be idempotent. See
[Fencing token](docs/05-jenkins.md#fencing-token).

### Documentation

Start with the [Quick start](docs/00-quick-start.md). The [documentation index](docs/README.md) links
the model, API guides, Jenkins reference, operations, security and recovery procedures.

### Modules

| Module | Purpose |
|---|---|
| `piplex-core` | Store-independent model and primitives |
| `piplex-discas` | `CoordinationStore` backed by [discas](https://github.com/green4j/discas) |
| `piplex-jenkins` | Jenkins plugin and four Pipeline steps |
| `piplex-example` | Runnable examples |

Implementations are constructed explicitly:

```java
CoordinationStore store = new DiscasCoordinationStore(client);
Piplex piplex = new Piplex(store, TimeSource.of(scheduler), observer);

// A host serving several environments asks for the primitives of one:
Piplex uat = piplex.in(Environment.of("uat"));
```

The modules are not published to Maven. A release contains one `.hpi` with the required modules and
client libraries.

### Build

Java 21 is required:

```text
./gradlew build
./gradlew :piplex-jenkins:jpi
```

The plugin baseline is Jenkins 2.555.1; CI also tests Jenkins 2.568.3. Its embedded discas client is
pinned by `discasVersion`; client and cluster nodes must run the same discas version.

### License

MIT. See [LICENSE](LICENSE).

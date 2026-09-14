## piplex

Cross-instance orchestration primitives for Jenkins pipelines.

Several Jenkins controllers run the same pipeline. `piplex` decides **which one of them is allowed to
run it**, and **when a pipeline on one controller may start because another controller finished its
work**. It keeps that decision in a coordination store shared by every controller, rather than in each
controller's own job configuration.

It is deliberately small. Jenkins stays the source of truth for execution -- builds, stages, logs,
artifacts, history. `piplex` only holds the little that has to be agreed on.

### The three primitives

**Exclusive run** -- at most one controller runs the work. Two policies behind one API: *elected*
(whoever takes the lease) and *designated* (an externally managed key names the owner, and the lease
then guards against two concurrent runs by that owner). Ownership is **held**, not checked once: if the
designation changes while a run is in flight, that run is revoked and stopped, and the new owner takes
over.

**Milestone** -- a signal carrying a generation. A producer publishes `key` at `generation`; consumers
wait until it reaches at least the generation they need. Publishing is monotonic and idempotent, so a
re-run never moves a milestone backwards. Waiting is level-triggered -- it compares state rather than
catching events -- which is what makes it survive a restart of the waiter.

**Operational switches** -- enable, disable and drain, changed with one write instead of editing
per-controller configuration and redeploying. Turning work off also stops the run
already in flight, through the same machinery that stops one whose owner changed.

### Documentation

**Start with the [Quick start](docs/00-quick-start.md)**: two controllers running one nightly job, end
to end, in about twenty minutes.

[`docs/`](docs/README.md) has the rest, with diagrams: [the model](docs/01-model.md) underneath all
three primitives, [exclusive run](docs/02-exclusive-run.md), [milestones](docs/03-milestones.md),
[switches](docs/04-switches.md), [the Jenkins plugin](docs/05-jenkins.md),
[operating piplex](docs/06-operations.md) and
[the discas cluster, ACLs and TLS](docs/07-discas.md).

### What piplex is not

- **Not a scheduler.** Jenkins computes cron from the job's own configuration, so a schedule cannot be
  read from the store at trigger time. Put a permissive cron on every controller and let `piplex` decide.
- **Not a store for deployment configuration.** It *is* a configuration store for one class of value:
  what has to change without a deploy and be agreed on everywhere at once -- who is primary and what is
  switched off. That is the third primitive, and calling it anything other than
  configuration would be a dodge.

  Bucket names, regions, image tags and job timeouts are the other class, and they stay in version
  control. Not because they are "configuration" and the switches are not, but because of what they need
  and this store does not have: review before the change, history after it, rollback, and changing two
  values as one. The question to ask of a value is never what it is called -- it is whether it must move
  faster than a deploy, and whether giving up review and history for it buys anything.
- **Not an event log.** History lives in Jenkins build history and in aggregated logs. `piplex` emits a
  fixed event vocabulary correlated by `key` + `generation` so that aggregation reconstructs the
  timeline across controllers.

### Guarantees, stated plainly

`piplex` guarantees that **at most one controller considers itself the owner** at a time. It does not
guarantee that at most one controller can still *affect* a protected resource: between a lease expiring
and its former holder noticing, both may believe they hold it. The `fencingToken` carried by an admitted
run is the mechanism that would close that gap, and it only closes it if the protected resource itself
rejects a stale token. If it does not -- and most do not -- overlap is possible, and stopping a run
half-way is not the same as making that half-finished work harmless. That part is a property of the work,
not of `piplex`.

Milestones compose well with this: a milestone is published only on success, so an interrupted producer
publishes nothing and consumers keep waiting rather than proceeding on a partial result.

### Modules

| Module | What |
|---|---|
| `piplex-core` | the model and the three primitives; depends on no coordination store |
| `piplex-discas` | `CoordinationStore` implemented over [discas](https://github.com/green4j/discas) |
| `piplex-jenkins` | the Jenkins plugin: three steps over the core, built as a `.hpi` |
| `piplex-all` | the two above, shaded into one artifact |
| `piplex-example` | runnable mains |

Implementations are **constructed explicitly** and passed in. There is no `ServiceLoader`, no
`META-INF/services`, and nothing is discovered at runtime:

```java
CoordinationStore store = new DiscasCoordinationStore(disCasClient);
Piplex piplex = new Piplex(store, TimeSource.of(scheduler), observer);
```

Time is one argument, not two. Inside it, the two readings are kept apart: every duration -- leases,
deadlines, renewals, how long a parked run waits -- is measured monotonically, and the wall clock is
used for one thing only, the timestamp written into a record for a person to read. A wall clock is
allowed to step, and a lease timed against one can lapse early, which is two owners at once.

Tests substitute `InMemoryCoordinationStore` through the same constructor, which is why the engine can be
tested in seconds without a cluster.

### The steps

```groovy
pipeline {
  agent none
  options {
    // not my region -> this build ends NOT_BUILT without allocating an agent;
    // my region and the designation changes mid-run -> the build is ABORTED and the new owner takes over
    piplexExclusive(key: 'eod', designatedBy: 'eod', generation: env.BUSINESS_DATE,
                    completedWhen: 'data/euc1', handoverWait: '4h')
  }
  stages {
    stage('EOD') { agent { kubernetes { } }; steps { sh './eod.sh' } }
  }
  post {
    success { piplexPublish key: 'data/euc1', generation: env.BUSINESS_DATE }
  }
}
```

```groovy
stage('Wait for EOD data') {
  steps { piplexAwait key: 'data/euc1', generation: env.BUSINESS_DATE, timeout: '90m' }
}
```

The cron then goes on **every** controller and piplex decides, instead of the schedule being commented
out of three controllers' configuration by hand.

The plugin is an adapter and nothing more: it parses parameters, calls the core, and turns an answer
into a Jenkins outcome. No step holds an executor while it waits. Install it by building
`./gradlew :piplex-jenkins:jpi` and deploying the resulting `.hpi`; it is not distributed through an
update centre.

### Build

`./gradlew build` compiles and tests everything on Java 17. Nothing else has to be checked out:
`discas` is an ordinary published dependency, pinned by `discasVersion` in `gradle.properties`. When
something is needed from it, that work happens in its own repository and is released there, and the
version here moves up.

**discas 0.0.2 is the floor**, and the client and the cluster nodes have to be that same version as each
other. Two separate reasons, both hard. An acquire whose fenced write lost to somebody who left the key
free answers `NOT_HELD` from 0.0.2 on, and this adapter reads that answer literally instead of guessing
at a lock reported held by nobody. And `CLIENT_HELLO` changed shape in 0.0.2 without the protocol
version changing with it, so a client and a node from either side of that release cannot talk at all --
and fail at the handshake rather than with anything that names a version.

### License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.

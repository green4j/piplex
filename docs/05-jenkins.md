## 5. The Jenkins plugin

The plugin configures one shared store per controller and exposes four Pipeline steps:
`piplexExclusive`, `piplexPublish`, `piplexAwait` and `piplexToken`.

[Stored keys](01-model.md#stored-keys) shows which discas key each step parameter reads or writes.

### Controller configuration

Open **Manage Jenkins > System > piplex**.

| Field | Meaning |
|---|---|
| `ownerId` | Stable identity named by designations; unique per controller |
| `clientId` | Identity presented to discas; defaults to `ownerId` |
| `environment` | Which set of orchestrations this controller's work belongs to; blank means `default` |
| `nodes` | Comma- or whitespace-separated `nodeId=host:port` entries |
| `token` | Shared token for discas token authentication |
| `tls` | Encrypt and authenticate the server connection |
| `tlsKeystore` | PKCS12 client certificate and key for mTLS |
| `tlsTruststore` | PKCS12 trust anchors for discas nodes |
| `tlsVerifyNodeIdentity` | Require a node certificate to name a configured node; default `true` |
| `watchPollPeriod` | Poll floor for background waits; blank uses the client default |

IPv6 addresses require brackets: `n1=[2001:db8::1]:7101`.

The supported security combinations are:

- trusted network: no token, no TLS;
- token authentication: token plus TLS, no client keystore;
- mTLS: TLS plus client keystore, no token.

Unsafe or ambiguous combinations fail before a store is built. See
[discas and security](07-discas.md).

Configuration as Code uses the `piplex` symbol:

```yaml
unclassified:
  piplex:
    # Name of this controller. Goal: let designations point at it.
    # Effect: builds run here only when a designation names this value
    ownerId: "euc1-blue"
    # Identity presented to discas. Goal: authenticate and apply ACLs per controller
    clientId: "piplex-euc1-blue"
    # discas cluster members. Goal: reach the shared state
    nodes: "n1=10.0.0.11:7101,n2=10.0.0.12:7101,n3=10.0.0.13:7101"
    # Shared secret, resolved from a CasC variable. Goal: authenticate to discas
    token: "${PIPLEX_TOKEN}"
    # Encrypted connection. Goal: protect the token; required with it
    tls: true
    # Goal: reject a server that is not a configured node.
    # Effect: the node certificate must name one of the nodes above
    tlsVerifyNodeIdentity: true
    # Trust anchors for discas node certificates
    tlsTruststore: "/run/secrets/discas-trust.p12"
    # Lower bound between polls in background waits. Goal: limit load on discas
    watchPollPeriod: "30s"
```

For mTLS, omit `token` and add `tlsKeystore` plus its password.
Token and password fields are Jenkins `Secret` values: encrypted in the configuration XML and
resolvable from CasC variables rather than written directly into YAML.

#### Saving configuration

One Save publishes all fields atomically to builds. Invalid `ownerId`, `nodes` or
`watchPollPeriod` values are rejected without replacing the current settings.

A successful Save closes the previous store. Runs using it stop renewing and are revoked when their
safe deadline is reached; the scheduler remains alive so those failures are observed. Change settings
when no guarded work is running, or accept that revocation.

The controller also writes `piplex/<environment>/instances/<ownerId>` every 30 seconds, in its own
default environment. Seeing another process mark under the same owner activates an administrative
warning and logs a warning in builds. Two controllers sharing an owner id in different environments
are two deployments rather than one duplicated, and are not reported.

### `piplexExclusive`

The step guards a body:

```groovy
// Whole build. Goal: guard every stage and post together
options {
    // Name of the protected work. Effect: one build at a time across controllers
    piplexExclusive key: 'eod',
                    // Designation to follow. Effect: runs only where it names this controller
                    designatedBy: 'eod-owner',
                    // Unit of work this build produces. Effect: checked against completedWhen
                    generation: params.BUSINESS_DATE
}

// Active site. Goal: follow external failover tooling.
// Effect: runs only while /dc/active holds this controller's ownerId
piplexExclusive(key: 'eod', activeWhenKey: '/dc/active') {
    sh './eod.sh'
}

// Block. Goal: guard only the enclosed steps.
// Effect: without designatedBy, the first controller to take the lease runs
piplexExclusive(key: 'eod') {
    sh './eod.sh'
}
```

The whole-build `options` form is appropriate for one indivisible operation. The block form guards a
stage or branch. A gate that returns before the work cannot stop that work later, which is why this is
a wrapper.

| Parameter | Default | Meaning |
|---|---|---|
| `key` | required | Resource being protected |
| `environment` | controller default | Which set of orchestrations this work belongs to |
| `designatedBy` | unset | Designation key; unset elects |
| `generation` | unset | Work generation |
| `completedWhen` | unset | Milestone that makes the generation unnecessary |
| `enabledBy` | unset | Shared and per-owner switches to consult |
| `activeWhenKey` | unset | External key that must hold `activeWhenValue`; not with `designatedBy` |
| `activeWhenValue` | `ownerId` | Value that admits this controller |
| `lease` | `60s` | Lease term and failed-holder handover bound |
| `renewEvery` | `lease / 3` | Renewal interval |
| `renewalGrace` | `lease` | Silence tolerated from lease operations |
| `guardGrace` | `renewalGrace` | Time a guard may remain unreadable |
| `handoverWait` | `0` | Time a candidate may park |

Durations accept `30s`, `90m`, `4h`, `7d` or ISO-8601 such as `PT1H30M`.

The step holds no executor while acquiring or parking. It derives `ownerId` from controller
configuration, `runId` from the build and a stable `executionId` from the block, so parallel asks by
one build do not share a lease.

The block returns `null`, because a body value cannot be recovered consistently across a controller
restart.

After the body ends, the step waits up to 15 seconds for release without blocking the CPS VM thread.
A failed or timed-out release is not a build failure; the lease is left to lapse before takeover.

#### Results

| Situation | Build result |
|---|---|
| Not designated, not active, lease held elsewhere, contention, already complete or disabled | `NOT_BUILT` |
| Guard record cannot be parsed or initial store call fails | `FAILURE` |
| Ownership is revoked after work starts | `ABORTED` |
| Ownership cannot be retaken on resume | `ABORTED` |
| Resume gets a different fencing token | `ABORTED` |
| Saved step state is newer than the installed plugin understands | `ABORTED` |

Each non-success carries a message or `CauseOfInterruption` that identifies the key and reason.

#### Completion order

With `completedWhen`, the milestone must be published before the exclusive lease is released.

In a declarative whole-build wrapper, `post` is inside `options`, so this is safe:

```groovy
options {
    piplexExclusive key: 'eod',
                    // Milestone the producer publishes. Goal: never redo a finished date.
                    // Effect: the build ends NOT_BUILT if it has this generation or later
                    completedWhen: 'data/euc1',
                    generation: params.BUSINESS_DATE
}
post {
    success {
        // Still inside the lease. Effect: no other build can repeat the generation
        piplexPublish key: 'data/euc1', generation: params.BUSINESS_DATE
    }
}
```

In a scripted pipeline, publish inside the block:

```groovy
piplexExclusive(key: 'eod', completedWhen: 'data/euc1',
                generation: params.BUSINESS_DATE) {
    sh './eod.sh'
    // Inside the block. Effect: published before the lease is released
    piplexPublish key: 'data/euc1', generation: params.BUSINESS_DATE
}
```

Publishing outside the scripted block creates a release-before-publish gap in which another candidate
can repeat the generation.

#### Restart

Live leases, timers and watches are not serialized. On resume the step asks again under the same
execution identity.

- A parked step waits only for the active portion of `handoverWait` not already spent. Controller
  downtime is not counted.
- A body already started is never started again and cannot park. It continues only if ownership is
  retaken with the same fencing token.
- If the lease was lost, the guards refuse admission, or a new acquisition has a higher token, Jenkins
  cancels the resumed body and marks the build `ABORTED`.
- Unknown newer saved-state versions fail rather than guessing during a plugin rollback.

`onResume()` cannot block, so Jenkins may replay a body while the asynchronous ownership check is in
flight. Only downstream fencing can make writes in that interval harmless.

When Jenkins stops, the plugin abandons admissions without releasing them. Renewals cease and leases
lapse; a quick restart can recover the same lease and token.

### Environments

Every key a step reads or writes is under `piplex/<environment>/`, so two environments on one cluster
share no milestone, switch, designation or lease. The controller's own setting is what steps get
unless they name another:

```groovy
// Reads and writes piplex/uat/..., on a controller whose default is prod
piplexExclusive(key: 'eod', environment: 'uat', designatedBy: 'eod') { ... }
```

Name it per step where one controller serves several environments -- the same job, parameterised by
the environment it is deploying. Leave it alone where a controller serves one: that is what the
controller-wide setting is for, and repeating it in every Jenkinsfile is a chance to get it wrong.

An `environment` must not contain `/`, because that is the separator between it and the kind of
record. A step that names one which cannot be an environment fails with that said, before it asks
for anything.

It is unrelated to Declarative Pipeline's own `environment { }` block, which sets environment
variables for the build and has nothing to do with keys.

### Fencing token

Fencing is optional. The guarded work need not know about piplex.

Without it, a former holder may still write between losing its lease and being stopped, so the work
should be idempotent. With it, the target remembers the highest token it has accepted and rejects
lower ones.

Inside an exclusive block, the token is available in two forms. `eod.sh` and `--fence` stand for
your job and its own option:

```groovy
// Token captured when the body started. Goal: let the target reject a stale holder
sh './eod.sh --fence "$PIPLEX_FENCING_TOKEN"'
// Token read now. Effect: fails the step if ownership is gone
writeFile file: 'fence.txt', text: "${piplexToken()}"
```

`PIPLEX_FENCING_TOKEN` is expanded once when the body starts. `piplexToken()` checks the live
admission and fails if ownership is gone. After restart the body is allowed to continue only when the
retaken lease has the same token; a different token aborts it.

Neither form protects anything unless the target resource rejects stale tokens.

### `piplexPublish`

```groovy
// Records that the date is produced. Goal: announce the result.
// Effect: waiting consumers continue; builds with completedWhen for the date skip
piplexPublish key: 'data/euc1',                 // Milestone to raise
              generation: params.BUSINESS_DATE, // Generation just produced
              environment: 'prod'              // Optional; the controller's default otherwise
```

Publish only after successful work. The step returns the generation in force. A restart repeats the
write safely because milestone publication is monotonic and idempotent.

### `piplexAwait`

```groovy
// Waits for the producer. Goal: start only after the date is produced.
// Effect: holds no executor while waiting
piplexAwait key: 'data/euc1',                 // Milestone to watch
            generation: params.BUSINESS_DATE, // Date needed, or any later one
            timeout: '90m',                   // Maximum wait
            skipOnTimeout: false              // On timeout: false fails, true ends NOT_BUILT
```

| Parameter | Default | Meaning |
|---|---|---|
| `key` | required | Milestone to read |
| `generation` | required | Minimum generation needed |
| `environment` | controller default | Which set of orchestrations to read it in |
| `timeout` | `1h` | Maximum wait for this controller session |
| `skipOnTimeout` | `false` | Return `NOT_BUILT` instead of `FAILURE` on timeout |

The wait holds no executor. A controller restart reads again but starts the timeout over. Stopping the
build cancels the wait within its current one-minute round.

### Branching

One `options` wrapper protects the whole pipeline DAG. If parallel branches protect independent
resources, give each branch a distinct key and publish a milestone for each independently consumable
result.

Do not:

- use the same exclusive key in parallel blocks of one build;
- catch and swallow `FlowInterruptedException` or use `catchError(catchInterruptions: true)` around
  guarded work;
- assume Jenkins can stop a detached process started by the body.

### Install and build

Install `piplex-<version>.hpi` from a
[release](https://github.com/green4j/piplex/releases), or build it with:

```text
./gradlew :piplex-jenkins:jpi
```

The plugin is not distributed through an update centre.

Previous: [4. Switches](04-switches.md) · Next: [6. Operations](06-operations.md) · [Documentation index](README.md)

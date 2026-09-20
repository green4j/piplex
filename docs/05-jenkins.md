## 5. The Jenkins plugin

The plugin configures one shared store per controller and exposes two sets of Pipeline steps. Four
are written into the jobs that do the work: `piplexExclusive`, `piplexPublish`, `piplexAwait` and
`piplexToken`. Four more change what the work is allowed to do: `withPiplexOperator` and the
`piplexDesignate`, `piplexSwitch` and `piplexInspect` steps used with it.

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

Configuration as Code uses the `piplex` symbol. `piplex-init` writes this file per controller -- see
[Deployment](09-deployment.md) -- and the fields are these:

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
warning and logs a warning in builds. Why the mark goes in the controller's own environment rather
than a step's, and what that means for two deployments, is in
[Stored keys](01-model.md#stored-keys).

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

| Parameter | Default | Meaning |
|---|---|---|
| `key` | required | The milestone to raise |
| `generation` | required | How far the producer got, usually the business date |
| `environment` | controller default | Which set of orchestrations it belongs to |
| `overwriteUnreadable` | `false` | Overwrite a record that does not parse. Against a readable one it changes nothing |

Publish only after successful work. The step returns the generation in force. A restart repeats the
write safely because milestone publication is monotonic and idempotent.

Stopping the build waits up to a minute for a write already sent, so the lease does not go back while
the milestone is still on its way. No longer: a store that has stopped answering must not hold the
executor until Jenkins kills the build.

`overwriteUnreadable` does not weaken that: monotonicity holds against a readable record either way,
so a milestone at or ahead of the generation given is still kept. It is one parameter with one
meaning on all three writing steps -- see
[6. A key holds something nothing can parse](06-operations.md#6-a-key-holds-something-nothing-can-parse).

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

### Operator steps

The steps above are written into the jobs that do the work. The four below are written into jobs
that change what the work is allowed to do, and they are kept apart on purpose: reading a Jenkinsfile
should say which of the two it is.

#### `withPiplexOperator`

Operator writes are made inside this block and nowhere else. A step that finds no block is refused
rather than falling back to this controller's identity, so there is no way to make an operator change
without the file saying that one is being made, and as whom.

```groovy
// Goal: act as the operations identity. Effect: writes inside are made as piplex-ops, and only jobs
// that can read the credential can make them
withPiplexOperator(credentialsId: 'piplex-ops-cert',  // The Jenkins credential carrying the identity
                   clientId: 'piplex-ops',            // The name the cluster's ACL grants
                   environment: 'prod') {             // Name it; see below
    piplexDesignate key: 'eod-owner', owner: 'euc1-green', reason: 'INC-4821'
}
```

| Parameter | Default | Meaning |
|---|---|---|
| `credentialsId` | none | The credential carrying the identity. Unset, the block acts as this controller |
| `clientId` | none | The discas identity to connect as, which is what an ACL grants. Required with a credential |
| `environment` | controller default | Which set of orchestrations to write in |

**Name the environment.** An operator job is copied between controllers, and a job inheriting
whatever the controller defaults to is how one meant for prod quietly writes to uat. The work steps
do not have this problem, because they live beside the work they guard.

With no `credentialsId` the block acts as this controller, which is what the shared identity of
[Who writes what](07-discas.md#who-writes-what) intends, and is what the Script Console did without
needing ADMINISTER. With one it acts as that identity -- see
[Operator credentials](#operator-credentials).

A block does not survive a controller restart. The credential was checked before the restart and not
after, and a handful of writes is cheap to run again.

#### `piplexDesignate`

```groovy
// Goal: move the work. Effect: the current holder is revoked and stops; a candidate parked on the new
// owner within its handoverWait takes over
piplexDesignate key: 'eod-owner',      // The key a request names in designatedBy
                owner: 'euc1-green',   // The ownerId that should hold the work
                reason: 'INC-4821'     // Written into the record, and the only account piplex keeps
```

| Parameter | Default | Meaning |
|---|---|---|
| `key` | required | The key a request names in `designatedBy` |
| `owner` | required | The `ownerId` that should hold the work |
| `reason` | none | Why, in the words a change record would use |
| `overwriteUnreadable` | `false` | Overwrite a record that does not parse. Against a readable one it changes nothing |

Returns the owner in force. Past `handoverWait` the new owner runs at its next scheduled build, which
for nightly work means the round is not done tonight: designating is a race against a deadline.

#### `piplexSwitch`

```groovy
// Goal: stop the work everywhere. Effect: running builds stop, new ones skip, nobody takes over
piplexSwitch key: 'eod-switch', enabled: false, reason: 'bad upstream data'

// Goal: take this controller out for maintenance. Effect: only this owner stops, and the step waits
// until work already running here has actually stopped
piplexSwitch key: 'eod-switch', enabled: false, ownerId: 'euc1-blue',
             reason: 'patching', drainTimeout: '30m'
```

| Parameter | Default | Meaning |
|---|---|---|
| `key` | required | The switch, as a request names it in `enabledBy` |
| `enabled` | required | Whether the work it guards may run |
| `reason` | none | Why, in the words a change record would use |
| `ownerId` | none | Drain one controller instead of stopping the work everywhere |
| `drainTimeout` | none | Wait this long for work already running **here** to stop |
| `overwriteUnreadable` | `false` | Overwrite a record that does not parse. Against a readable one it changes nothing |

Without `ownerId` this is the shared switch and halts the work itself; with one it is maintenance and
leaves the work running elsewhere. They are different decisions with very different blast radii, and
a job that can do one need not be a job that can do the other.

`drainTimeout` is only for draining **this** controller. An admission is held by a process and no key
records which processes are still in one, so the question can only be answered locally -- and
answering it for another controller would report it quiet while it was still working. The step
refuses rather than doing that.

Draining another owner without it is allowed: the switch is that owner's key, and writing it is a
real change. Only the waiting is impossible, so the step logs what it did not check:

```text
piplex: NOT CONFIRMED 'euc1-green' takes no new work, and nothing here can see what that
controller is still running. Run the drain on 'euc1-green' itself, which is where the wait
can be answered
```

The same line, pointing at this controller, follows a drain of the local owner with no
`drainTimeout`. A build is green as soon as the switch is written, and "the switch is off" is not
the answer somebody about to patch a machine is after.

#### `piplexInspect`

```groovy
// Goal: find out why tonight's round has not run. Effect: reads every guard and says what is
// stopping it, in one answer
def why = piplexInspect key: 'eod', designatedBy: 'eod-owner', enabledBy: 'eod-switch',
                        completedWhen: 'data/euc1', generation: env.BUSINESS_DATE,
                        activeWhenKey: '/dc/active', activeWhenValue: 'euc1-blue'
```

Takes the same parameters as `piplexExclusive`, so a diagnosis is written by copying the request being
diagnosed. Returns what is stopping the work, empty when nothing is. Needs no operator identity and no
block: every controller can read its own guards however the ACL is written, and reading grants nobody
anything. It has no `overwriteUnreadable`, and that is deliberate -- it is the one step everyone may
run, and a write parameter on it would make "may diagnose" and "may overwrite shared state" one
permission.

Each guard is read on its own, so one that will not parse is reported `UNREADABLE` with the job and
parameters that put it back, and the remaining guards are still read. That is the case this step
exists for, so it is the case it must survive rather than fail on.

Two warnings can precede the reading, and both mark the build `UNSTABLE`: another live controller
using this owner id, and a heartbeat not written lately, which means the first check is not running.
Neither stops the work, so neither is returned as a blocker.

It cannot say who holds the lease. There is no read-only way to ask -- the only way to learn the
holder is to try to take it -- so where the guards explain nothing it bounds its answer instead of
naming a cause it never established: a run holding the lease is one of the things left, its build log
naming `ownerId/runId`, and the schedule never firing is another.

### Operator credentials

Where [the operator has its own identity](07-discas.md#does-the-operator-get-its-own-identity) it is not this
controller's, and it reaches a job as a Jenkins credential.

| Cluster mode | Credential kind |
|---|---|
| `mtls` | Certificate, holding the PKCS12 whose CN is the `clientId` |
| `token` | Secret text, holding the token |
| `allowall` | None, and a second identity is refused: a client id there is a claim, not a proof |

**Where it lives is the access control.** The credential is resolved against the running build, not
read from the global configuration, so a folder-scoped credential is invisible to jobs outside that
folder -- and that is the whole enforcement. Put the operations credential in the folder holding the
operator jobs.

Do not put it in the global store. Every job on the controller could then resolve it, which hands
them all the identity the ACL separated out, and the separation survives only in the ACL file.

**Job/Configure in that folder is the right to act as the operations client.** Whoever can edit a
Jenkinsfile there can write a block that uses the credential. Grant it as that, not as permission to
edit a job.

One name runs through all four levels, and a mismatch shows up at whichever one it was typed into:

```text
certificate CN = piplex-ops
Jenkins credential 'piplex-ops-cert' in folder 'ops', holding that certificate
acl.piplex-ops = piplex/prod/designated/:GC ; piplex/prod/enabled/:GC
withPiplexOperator(credentialsId: 'piplex-ops-cert', clientId: 'piplex-ops')
```

**Rotation** replaces the credential, and nothing else: the block builds its client when it runs, so
the next operator build uses the new one. No Save, and no rebuilding of the store the work is running
on -- unlike rotating this controller's own certificate, which closes the store and revokes what it
was holding.

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

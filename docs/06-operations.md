## 6. Operating piplex

This chapter is the runbook for changing shared state and diagnosing builds. Persistent formats and
recovery are covered in [Lifecycle](08-lifecycle.md).

### What an operator does

Piplex knows nothing about the work itself. It records what you assert about it, and every controller
then decides from that record. So each procedure below is the same two moves: establish what is
actually true, then make the shared state say it. Getting that wrong is not a failed command -- it is
a record that disagrees with reality, which every controller will act on.

Six situations account for all of it. Each names the step that serves it; the steps themselves are in
[The Jenkins plugin](05-jenkins.md#operator-steps).

#### 1. Tonight's round has not run

The commonest one. A build ended `NOT_BUILT` or is waiting, and the question is whether today's round
will be produced at all.

Ask, from any controller on the cluster:

```groovy
// Goal: one answer instead of five reads. Effect: prints what is stopping this work, if anything
piplexInspect key: 'eod', environment: 'prod', designatedBy: 'eod-owner',
              enabledBy: 'eod-switch', completedWhen: 'data/euc1',
              generation: params.BUSINESS_DATE,
              activeWhenKey: '/dc/active', activeWhenValue: 'euc1-blue'
```

Five things can stop it, and they want different answers: nobody is designated, the work is switched
off, this owner is drained, the generation is already complete, or the active key names somewhere
else. The first three you change; the fourth means there is nothing to do; the last belongs to the
system that writes that key.

If none of the five is stopping it, the step says so and stops there rather than naming a cause it
never established: it cannot read a lease, and there is no read-only way to. What is left is either a
run already holding it -- whose own build log names `ownerId/runId` -- or something that is not piplex
at all: the schedule, the queue, an executor, a controller pointed at another cluster.

Two warnings can appear above the reading, and both make the build UNSTABLE, because a line in a log
is easy to walk past. Another live controller using this owner id means a designation naming it names
two machines, and which of them runs is chance. A heartbeat that has not been written means that check
is not running, so the first warning cannot be ruled out either; an ACL without `instances/` is the
usual reason.

A key that will not parse does not end the diagnosis. It is reported `UNREADABLE`, the remaining guards
are still read, and the job that puts it back is named -- see 6.

**This is a race.** `handoverWait` bounds how long a parked candidate waits without an executor. Fix
the state inside it and the work runs tonight; miss it and the next attempt is the next scheduled
build, which for a nightly job means the round is not produced today.

#### 2. Move the work to another site

Planned, or because a site failed. The work must run in the new place, not the old, and on no account
in both.

Where an external key drives it, do nothing: the failover tooling moves `/dc/active` and piplex
follows. Where piplex holds the designation:

```groovy
withPiplexOperator(credentialsId: 'piplex-ops-cert', clientId: 'piplex-ops', environment: 'prod') {
    // Goal: hand the work to green. Effect: blue is revoked, stops and releases; green takes over
    piplexDesignate key: 'eod-owner', owner: 'euc1-green', reason: 'INC-4821'
}
```

The holder is revoked, stops and releases; a candidate parked on the new owner within its
`handoverWait` takes over. Past it, the same deadline as above applies.

#### 3. Take a controller out, and put it back

Patching, a plugin upgrade, a discas upgrade, host work. This controller must stop taking work while
the others carry on, and whatever is running here must end in the open rather than vanish.

Be clear about what draining does: it **ends** the run here, it does not let it finish. The switch
revokes it, Jenkins cancels the body so the pipeline's own `post` steps run, and the lease goes back
at once. The alternative is not gentler but worse -- a reboot kills the process with the lease in its
hand, and that lease is then not released but left to expire, so nobody picks the work up until it
does, not even an owner designated meanwhile. Either way the round stops here; the choice is whether
it stops visibly.

**Run this on the controller being taken out.** Whether work is still running is a question about
processes, and only that controller can answer it.

```groovy
withPiplexOperator(environment: 'prod') {
    // Goal: stop taking work here and wait for what is running to finish or be revoked
    piplexSwitch key: 'eod-switch', enabled: false, ownerId: 'euc1-blue',
                 reason: 'patching', drainTimeout: '30m'
}
```

The step returns only when nothing this switch governs is still running here, and fails if that has
not happened within the timeout. A failure means the controller is **not** safe to touch.

Running, and not merely admitted. Disabling the switch revokes the admission at once, but revoking
only asks Jenkins to stop the body, and a body runs until it notices. The wait is for the body, which
is what somebody about to patch a controller is actually waiting for.

Run without `drainTimeout` and the switch is still written, but nothing has been waited for, and the
step says so:

```text
piplex: NOT CONFIRMED 'euc1-green' takes no new work, and nothing here can see what that
controller is still running
```

Take that line at face value. The build is green because the record changed, which is a different
statement from the one maintenance needs.

Asking for `drainTimeout` against another controller's owner is refused instead, and nothing is
written: the wait looks at processes here, so run from anywhere else it would return at once having
confirmed nothing.

**One switch is not a drain.** The question being asked is about the machine, so everything running on
it has to stop: the generated `drain-controller` writes a per-owner disable on *every* switch the
estate knows, and only then waits on each. Written one at a time, the work the second switch guards
goes on starting here throughout the wait on the first.

It reaches only work that names `enabledBy`. The key it writes is that work's own per-owner switch, so
work that is designated but names no switch is not drained by this and carries on running --
`piplex-init` reports that when the estate is written, because it is the one way a controller reported
drained can still be busy.

Jenkins does not undo what was already done and cannot stop a detached process, so partial effects of
the cancelled run remain, and piplex will not roll them back.

Afterwards, `enabled: true` on the same key, with the same `ownerId`.

A discas upgrade adds one requirement: wait at least the longest lease term before touching the
cluster, or work may still be acting under a lease taken before it.

#### 4. Stop the work everywhere, and resume it

An incident: bad input data, a defect found, an instruction from outside. Stop now, everywhere,
without editing and redeploying jobs -- which is what switches exist for.

```groovy
withPiplexOperator(credentialsId: 'piplex-ops-cert', clientId: 'piplex-ops', environment: 'prod') {
    // Goal: halt the work itself. Effect: running builds stop, new ones skip, nobody takes over
    piplexSwitch key: 'eod-switch', enabled: false, reason: 'bad upstream data'
}
```

Note what this is not: it is not draining a controller. It halts a business process, and it is worth
being a separate job with separate rights for that reason.

**A switch belongs to a business process, not to a pipeline.** If tonight's round is produced by five
pipelines, one switch stops the round and "stop the round" stays one decision with one radius. Give
work its own switch where the decision to stop it is genuinely separate -- reference data can be held
without holding the trading day. Different schedules are not a reason to split: the reason is
different responsibility for stopping. Split by pipeline instead and the stop job becomes a list of
thirty keys somebody has to choose between at three in the morning.

Designation stays per piece of work: where `eod` runs and where `new-day` runs are independent
questions, and they are handed over separately.

When the cause is fixed, `enabled: true`. Whether the missed round has to be caught up is a decision
piplex does not make for you.

#### 5. Announce a completion that was never announced

The producer did the work and did not publish it -- it died between finishing and publishing, or the
record was lost. Consumers elsewhere are waiting and the day is stalled while the data exists.

Check that it really does exist. Then publish it **from the controller that produces it**, which is
the one whose identity may write that milestone:

```groovy
// Goal: release the waiters. Effect: consumers continue, and later builds for the date skip
piplexPublish key: 'data/euc1', generation: '2026-09-14', environment: 'prod'
```

Consumers trust this exactly as much as they trust the producer's own publication, because it is the
same assertion. Publishing a round that was not produced starts downstream work on data that is not
there, and it is the worst mistake available here.

#### 6. A key holds something nothing can parse

Rare, and it stops the work for good until somebody acts. Piplex only ever writes records it can read
back, so this arrives from outside: a key edited by hand through `discas-agent` or `curl`, a store
restored from a backup holding another format, or a controller rolled back to a plugin that does not
understand what a newer one wrote.

Every ordinary operation reads before it writes and refuses what it cannot read, so one unparseable
value fails every write to that key as well as every read. Guards fail closed, and the work stays
stopped. What that looks like differs by key:

| Corrupt key | What happens to the work |
|---|---|
| `designated/<key>` | Runs in flight are revoked `GUARD_UNREADABLE`; no candidate is admitted |
| `enabled/<key>`, `enabled/<key>/@<ownerId>` | The same. An absent switch means enabled; an unreadable one does not |
| `milestone/<key>` | Nothing is revoked -- it is not watched -- so the run works to the end and then cannot publish. Consumers in `piplexAwait` fail at once instead of waiting |

`piplexInspect` names the key and the job that puts it back. That job is the one which owns the key
anyway, run with `OVERWRITE_UNREADABLE` ticked:

| Corrupt key | Job |
|---|---|
| `designated/<key>` | `handover` |
| `enabled/<key>` | `stop-work` |
| `enabled/<key>/@<ownerId>` | `drain-controller`, on that controller |
| `milestone/<key>` | `announce-completion`, on the controller that produces it |

The flag changes exactly one thing: a value that will not parse is treated as absent rather than
failing the write. Against a readable record the operation is what it has always been -- a milestone
at or ahead of the generation given is still kept, and this cannot move one backwards. The identity,
the rights and the risk are therefore those of the ordinary operation, which is why there is no
separate repair job and no separate credential for one.

What is lost is what the old value said. A repaired designation starts its `seq` again at 1, and a
repaired milestone names no run; `seq` is informational and nothing fences on it.

Where the data was simply wrong rather than the record unreadable, this is not the tool: milestones
only move forward, and the answer is to move on to a new generation.

[Lifecycle](08-lifecycle.md) has the rest, including what a restored store does to fencing tokens.

### Keys, for when you need the store itself

[Stored keys](01-model.md#stored-keys) maps every step parameter and operation to its discas key.

| Key | Inspect for |
|---|---|
| `piplex/<environment>/designated/<designatedBy>` | Current designated owner |
| `piplex/<environment>/enabled/<enabledBy>` | Shared switch |
| `piplex/<environment>/enabled/<enabledBy>/@<ownerId>` | Per-owner drain |
| `piplex/<environment>/milestone/<key>` | Highest completed generation |
| `piplex/<environment>/exclusive/<key>` | Store-native lease; never edit manually |
| `piplex/<environment>/instances/<ownerId>` | Jenkins duplicate-owner heartbeat, in the controller's own environment |

The first four value records are readable JSON. The exclusive record is binary/store-native.

`piplexInspect` reads the first four by name and is the answer for everything above. Two questions it
cannot answer need the store directly, through a `discas-agent` sidecar and `curl`: listing what keys
exist under a prefix, which piplex never does and does not ask `S` for, and inspecting a lock, which
has no read-only form in piplex at all. Both are forensics rather than operating, and neither is part
of any procedure here.

### Diagnosing a build

Most of this table is one `piplexInspect` call, which names whichever of the first six applies. It is
here for the cases that call is not the answer to.

| Build says | Check or do |
|---|---|
| Another owner is designated | `piplexInspect`, then `piplexDesignate` if it should move |
| Nobody is designated | `piplexDesignate` once |
| Another execution holds the lease | Inspect the owner/run named in the message; no step reads a lease |
| Another acquire won the write race | Retry or let `handoverWait` take another look |
| The generation is already complete | `piplexInspect` with `completedWhen` and `generation` |
| Work is disabled | `piplexInspect` reads the shared and per-owner switches together |
| A guard is unreadable | `piplexInspect` names the key and the job; rerun that job with `OVERWRITE_UNREADABLE` |
| No credential is available to this job | The job cannot read it: check the folder it is in, and the folder the credential is in |
| The block asks to act as somebody over plaintext | A second identity needs token or mTLS; see [Who writes what](07-discas.md#who-writes-what) |
| A write is refused by the cluster | The ACL does not grant that identity that key. The identity is on the `OPERATOR as=` line |
| Owner id is missing | Configure `ownerId` |
| discas nodes are missing | Configure `nodes` |
| A node entry is malformed | Correct the field rejected on Save |
| Ownership was revoked | Read its `CauseOfInterruption` and `REVOKED` log line |
| Ownership was lost on resume | Check lease expiry, designation and switch changes during downtime |
| Resume received a different token | Treat the old body as fenced out and run again |

Client/node version mismatch commonly appears as a handshake or decoding error rather than a message
that names versions. The embedded discas client and every node must use the same release.

### Event vocabulary

Every decision is logged as one event followed by `key=value` fields:

```text
piplex: ADMITTED environment=prod key=eod generation=2026-09-14 owner=euc1-blue run=eod#142 fencingToken=8
piplex: REVOKED environment=prod key=eod generation=2026-09-14 owner=euc1-blue run=eod#142 reason=DESIGNATION_CHANGED newOwner=euc1-green
piplex: NOT_DESIGNATED environment=prod key=eod generation=2026-09-14 owner=apac1 currentOwner=euc1-blue
```

Correlate by `environment`, `key` and `generation` across controller logs. The environment is on
every line because one controller may run the same key in several of them, and two such lines are
otherwise indistinguishable. Important revocation reasons:

| Reason | Interpretation |
|---|---|
| `DESIGNATION_CHANGED` | Planned handover |
| `DISABLED` | Planned drain; `detail` carries the operator reason |
| `LEASE_LOST` | The store answered that the lease is gone |
| `RENEWAL_FAILED` | The renewal outcome remained unknown until ownership had to end |
| `GUARD_UNREADABLE` | The stored record will not parse; see [6](#6-a-key-holds-something-nothing-can-parse) |
| `GUARD_UNREACHABLE` | One guard could not be confirmed, often because of ACLs |

The store is not an event log. Build history or aggregated logs provide audit and reconstruction.

### Operational constraints

- A lease is the failed-holder handover bound, not the work duration. Leave it near the 60-second
  default unless measured store latency requires otherwise.
- Saving any valid plugin setting closes the old store and eventually revokes its runs. A rejected
  Save changes nothing.
- In `AllowAll`, client ids are claims rather than authenticated identities.
- Jenkins cancellation cannot undo partial work or stop detached processes.
- A lease does not eliminate stale-holder overlap. Pass the fencing token to a resource that enforces
  it, or make the work idempotent. See [Guarantees](01-model.md#guarantees).
- `catchError(catchInterruptions: true)` and catching `FlowInterruptedException` can let guarded work
  continue after revocation.

### Watch cost

Discas watches poll. At `LINEARIZABLE`, every poll is a consensus read.

| Waiter | Concurrent watches | Poll floor |
|---|---|---|
| Parked exclusive candidate | Up to 4: designation, shared switch, owner switch, milestone | Configured `watchPollPeriod` |
| Admitted run | Up to 3: designation, shared switch, owner switch | Client default |
| Milestone waiter | 1 | Configured `watchPollPeriod` |

Only keys named by the request are watched. Actual gaps are jittered between one and five times the
period.

#### Sizing an estate

Those figures are per waiter. What the cluster sees is per guarded job, multiplied by the controllers
that could run it: one is admitted and the rest park, so

```text
watches per job = 3 (the admitted run) + 4 x (controllers parked)
```

Four controllers sharing one nightly job is up to 15 concurrent watches; ten such jobs, up to 150 --
each one a linearizable read, which is a consensus round. They are ceilings, because only the keys a
request names are watched: a job naming no `completedWhen` parks on three rather than four, and a
second job on the same guards watches them a second time, since watches are per request and not
shared.

Raise `watchPollPeriod` first, which is what the parked majority polls at; the jitter above then
spreads them rather than letting four controllers ask together. It does not reduce the admitted run's
three, and nothing should -- those answers revoke work.

Set **Manage Jenkins > System > piplex > watch poll period** for background waits. Typical starting
points are 30 seconds to 2 minutes for nightly work and 5 to 15 seconds for frequently switched work.
The minimum is 500 ms; blank uses the client default.

This field never slows watches held by an admitted run. Those remain at the client period because
their answers directly revoke work. Parked candidates re-read every round, normally every
`renewEvery` (20 seconds with the default lease), so a longer background period reduces polls without
changing correctness.

All plugin watches use `LINEARIZABLE`. `SERIALIZABLE` is not exposed because an admitted run acts on a
watch result without a second read; stale guard state would delay revocation.

Previous: [5. Jenkins](05-jenkins.md) · Next: [7. discas and security](07-discas.md) · [Documentation index](README.md)

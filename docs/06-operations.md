## 6. Operating piplex

This chapter is the runbook for changing shared state and diagnosing builds. Persistent formats and
recovery are covered in [Lifecycle](08-lifecycle.md).

### Access the operator API

There is no operator CLI or Pipeline step. From **Manage Jenkins > Script Console**:

```groovy
import hudson.model.TaskListener
import io.github.green4j.piplex.jenkins.PiplexConfiguration

def piplex = PiplexConfiguration.get().piplexFor(TaskListener.NULL)
```

That gives the controller's default environment. Every procedure below changes only the environment
it was asked for, so name the one you mean where a controller serves several:

```groovy
def uat = PiplexConfiguration.get().piplexFor(TaskListener.NULL, 'uat')
```

Prefer an authenticated job using its real `listener`; it provides an approval trail and writes the
operation to that build's log. Always wait for completion before proceeding:

```groovy
piplex.designations()
      .designate('eod-owner', 'euc1-green', 'INC-4821')
      .toCompletableFuture().join()
```

### Common procedures

#### Hand over work

```groovy
piplex.designations()
      .designate('eod-owner', 'euc1-green', 'INC-4821')
      .toCompletableFuture().join()
```

The current holder is revoked, stops, and releases. A green candidate parked within `handoverWait`
wakes and takes over; otherwise the next scheduled green build runs.

#### Disable work everywhere

```groovy
piplex.switches().disable('eod-switch', 'INC-4821').toCompletableFuture().join()
```

Runs in flight stop and no owner takes over. Re-enable with:

```groovy
piplex.switches().enable('eod-switch').toCompletableFuture().join()
```

#### Drain one controller

```groovy
piplex.switches()
      .disable('eod-switch/@euc1-blue', 'patching blue')
      .toCompletableFuture().join()
```

Only requests with `enabledBy: 'eod-switch'` and owner `euc1-blue` stop. Re-enable the same key after
maintenance.

#### Unblock a milestone waiter

Publish only if the required data actually exists:

```groovy
import io.github.green4j.piplex.Generation

piplex.milestones()
      .publish('data/euc1', Generation.of('2026-09-14'), null, null)
      .toCompletableFuture().join()
```

This is the same promise a producer makes and consumers trust it equally.

### Inspecting keys

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

### Diagnosing a build

| Build says | Check or do |
|---|---|
| Another owner is designated | Read `piplex/<environment>/designated/<designatedBy>` |
| Nobody is designated | Run `designate()` once |
| Another execution holds the lease | Inspect the owner/run named in the message |
| Another acquire won the write race | Retry or let `handoverWait` take another look |
| The generation is already complete | Read `piplex/<environment>/milestone/<completedWhen>` |
| Work is disabled | Read the shared and per-owner switches |
| A guard is unreadable | Repair the key from known-good state |
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
| `GUARD_UNREADABLE` | Stored JSON must be repaired |
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

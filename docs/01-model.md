## 1. The model

Several controllers make decisions from one shared coordination store. They never call one another,
and none keeps a private event cursor that must survive a restart.

### Store contract

`CoordinationStore` provides:

- a linearizable read and version-fenced compare-and-set per key;
- a bounded wait for a key to move past a version;
- a lease with a strictly increasing fencing token.

Operations are asynchronous. A failed operation may have taken effect unless its implementation says
otherwise. Retrying a compare-and-set is safe because a stale expected version cannot apply twice.
Every call is bounded by `responseBound`; a wait receives its requested duration in addition to that
backstop.

`awaitChange` is a query, not an event subscription. It may coalesce several writes and return only
the latest state. At the end of a partial outage it may return the newest state it saw as
`confirmed=false`.

### Compare state, not events

Every loop follows the same rule:

1. read the current value and version;
2. decide whether the condition is satisfied;
3. wait for the version to change, or for a bounded round to end;
4. read and decide again.

This survives coalesced changes, missed wake-ups and process restarts. It also gives the right answer
when a value changes away and back: the state in force matters, not an intermediate event.

### Stored keys

Every name used by a run or an operator maps to its own discas key. Different kinds of names map to
different keys, even when the names are equal. Examples use the quick start names and the `prod`
environment.

Every key piplex writes is `piplex/<environment>/<kind>/<name>`. The environment segment is what lets
one cluster serve several sets of orchestrations: `prod` and `uat` share no milestone, no switch, no
designation and no lease, even where every name in them is the same. It is set once per controller in
Manage Jenkins > System and may be overridden per step; a controller which names none works in
`default`.

| Name in piplex | discas key | Example | Read by | Written by |
|---|---|---|---|---|
| `key` of `piplexExclusive` / `ExclusiveRequest` | `piplex/<environment>/exclusive/<key>` | `piplex/prod/exclusive/eod` | The run | The run: acquire, renew, release |
| `designatedBy` | `piplex/<environment>/designated/<designatedBy>` | `piplex/prod/designated/eod-owner` | Runs; watched while parked or admitted | Operator: `designate`, `repair` |
| `enabledBy` | `piplex/<environment>/enabled/<enabledBy>` | `piplex/prod/enabled/eod-switch` | Runs; watched while parked or admitted | Operator: `disable`, `enable`, `repair` |
| `enabledBy` plus the controller's `ownerId` | `piplex/<environment>/enabled/<enabledBy>/@<ownerId>` | `piplex/prod/enabled/eod-switch/@euc1-blue` | Runs of that owner; watched while parked or admitted | Operator: `disable('eod-switch/@euc1-blue', ...)` |
| `activeKey` / `activeWhenKey` | `<activeKey>`, as is | `/dc/active` | Runs; watched while parked or admitted | External system only |
| `completedWhen` | `piplex/<environment>/milestone/<completedWhen>` | `piplex/prod/milestone/data/euc1` | Runs; watched while parked | Producer, see the next row |
| `key` of `piplexPublish` / `publish` | `piplex/<environment>/milestone/<key>` | `piplex/prod/milestone/data/euc1` | Producer, before its compare-and-set | Producer; operator `repair` |
| `key` of `piplexAwait` / `awaitAtLeast` | `piplex/<environment>/milestone/<key>` | `piplex/prod/milestone/data/euc1` | Consumer, watched | Producer, see the previous row |
| Jenkins `ownerId` | `piplex/<environment>/instances/<ownerId>` | `piplex/prod/instances/euc1-blue` | The same controller | The controller, every 30 seconds |

Other properties are not keys:

- `environment` is a segment of every key above, not a key of its own;
- `generation` is compared with the `generation` field inside the milestone record;
- `ownerId`, `runId` and `executionId` name the lease holder; the lease record keeps that name, the
  fencing token and the `lease` term;
- `renewEvery`, `renewalGrace`, `guardGrace`, `handoverWait` and `timeout` are local timings.

The active key is the only key outside `piplex/`, in any environment. Its value is a plain string,
not JSON, and piplex never writes it. It is not given an environment segment either: it belongs to
the external system that writes it, which separates its own environments however it already does.

`piplex/<environment>/instances/` is not work state, but its prefix must be included in Jenkins
ACLs. The heartbeat is written in the controller's own default environment, never a step's: it says
which process is using an owner id, and a controller has one identity however many environments its
jobs run in. Two controllers sharing an owner id in different environments are therefore two
deployments rather than one duplicated, and no warning is raised.

The designation, switch and milestone records are JSON intended for inspection. The exclusive key is
a store-native lock record and must never be edited by hand.

### Generations

A generation identifies a round of work: a business date, batch id or sequence. Piplex compares its
string lexicographically. ISO-8601 dates therefore order correctly; numeric counters must be
zero-padded (`09`, `10`).

Milestones move only forward according to this ordering.

### Identity

`ownerId` identifies a deployment or Jenkins controller. It is stable operational identity, not a
hostname or URL that may change.

A lease identifies one execution as:

```text
ownerId/runId[/executionId]
```

Each part is escaped before joining. `runId` separates builds on one owner; `executionId` separates
concurrent exclusive asks made by one run. A retry must use the same values so that an acquisition
whose result was lost is recovered as `HeldBySelf`.

### Time

Elapsed time uses a monotonic clock. Wall time is used only for human-readable timestamps in records.
This prevents NTP corrections or clock changes from moving lease and wait deadlines.

Lease information crosses process boundaries as a remaining duration, never as another machine's
clock reading.

### Guarantees

Piplex guarantees:

- at most one live lease on a key in the coordination store;
- a fencing token strictly higher than every earlier acquisition of that key;
- local ownership ends no later than the lease term known to the holder;
- designation and switch changes revoke a holder once its watch observes the new state;
- milestones are monotonic and waits compare state.

Piplex does **not** guarantee that only one process believes it owns the work at every instant. A lease
may expire before its former holder observes the loss. It also cannot undo partial work or stop a
process Jenkins cannot reach.

The fencing token closes the stale-holder window only when the protected resource remembers the
highest token it has accepted and rejects lower ones. Without such fencing, make the work idempotent
and check `isHeld()` before irreversible steps.

Publishing a completion milestone is not transactionally tied to the lease. Publish while admission
is still held; `Admitted.completeAndRelease()` does this in the correct order.

Previous: [Quick start](00-quick-start.md) · Next: [2. Exclusive runs](02-exclusive-run.md) · [Documentation index](README.md)

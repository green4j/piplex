## 8. Upgrades, rollback and recovery

Piplex persists JSON records, store-native leases and Jenkins step state. These formats evolve
compatibly where possible, but key naming, lease identity and serialized Pipeline context still make
upgrade and rollback operational procedures.

### Effects of lifecycle events

| Event | Running work | Stored coordination state |
|---|---|---|
| Valid Jenkins configuration Save | Old store closes; runs are revoked by failed renewal or guard access | Preserved |
| Jenkins restart | Live timers disappear; resumed steps ask again | Preserved |
| Cluster outage | New admission fails; holders stop by grace or lease term | Preserved if cluster recovers |
| Plugin/client and node version mismatch | Connection handshake fails | Preserved |
| Store loss or rewind | Holders and fencing sequence are lost | Must be reconstructed |

### Upgrade the plugin

The plugin is installed from a release `.hpi`, not an update centre.

1. Drain or hand over work. To move one key:

   ```groovy
   piplex.designations()
         .designate('eod-owner', 'euc1-green', 'plugin upgrade')
         .toCompletableFuture().join()
   ```

   To stop all owners instead:

   ```groovy
   piplex.switches().disable('eod-switch', 'plugin upgrade').toCompletableFuture().join()
   ```

2. Wait for guarded builds to finish or confirm they were revoked.
3. Install the `.hpi` and restart one controller at a time.
4. Run a build that reaches the store; the settings page alone does not test connectivity.
5. Repeat for the remaining controllers, then restore designations or switches.

Controllers running different plugin versions communicate only through stored formats. The discas
client embedded in each `.hpi`, however, must match the cluster nodes exactly.

Avoid upgrading under a running `piplexExclusive` body. Jenkins serializes plugin-owned context with
that body; a version lacking one of those classes cannot deserialize it. A resumed body also aborts if
its lease was taken and returned during downtime and it reacquires with a higher fencing token.

### Roll back the plugin

Rollback is installation of an older `.hpi` followed by restart. Before doing it:

- finish or drain every exclusive body;
- verify the older version understands the saved step-state version;
- review release notes for key-name or lease-identity changes;
- keep the discas client and nodes on the same version.

Designation, switch and milestone records are forward-tolerant when their acted-on fields remain
present: `owner`, `enabled` and `generation`. Unknown provenance is ignored. Operational writes do not
roll back automatically.

A lease left by the removed process lapses within its configured term. Do not edit
`piplex/exclusive/<key>` to accelerate it.

#### Compatibility with older key formats

Older installations may store a per-owner switch as `<key>/<ownerId>` rather than the current
`<key>/@<ownerId>`. Upgrade and rollback do not move that state; reissue any active drain under the
format read by the installed plugin.

Lease identity now escapes `/` and `\` inside its owner, run and execution components. A resumed run
whose older identity contained either character may not recognise the lease it left and will wait for
that lease to lapse before acquiring normally. Identities without those characters are unchanged.

### Upgrade discas

Treat the cluster and every plugin client as one compatibility unit:

1. Disable each affected work key and wait for completion:

   ```groovy
   piplex.switches().disable('eod-switch', 'discas upgrade').toCompletableFuture().join()
   ```

2. Wait at least the longest lease term so no run can still act under an old lease.
3. Upgrade the nodes according to discas documentation.
4. Deploy the `.hpi` containing the matching client to every controller.
5. Verify designation and milestone keys.
6. Re-enable each key and run a build:

   ```groovy
   piplex.switches().enable('eod-switch').toCompletableFuture().join()
   ```

Skipping the drain does not migrate or corrupt records, but turns the version mismatch into aborted
builds.

### Loss of quorum

Do not modify piplex keys while the cluster lacks quorum.

- New runs fail closed because guards and leases cannot be confirmed.
- Existing holders continue only until `renewalGrace` or their current lease term ends, whichever is
  earlier.
- Failed releases may leave leases standing until their terms lapse.

Restore the cluster, then inspect the first builds that use each key. Never hand-write an exclusive
record.

### Store loss or rewind

Reconstruct shared state per work key:

| State | Recovery | If omitted |
|---|---|---|
| Designation | Run `designate()` with the intended owner | Nobody is designated |
| Shared and owner switches | Reapply the intended enabled/drained state | Missing switches mean enabled |
| Milestone | Publish only the generation known to be complete | Consumers wait or accept stale state |
| Lease | Do not recreate manually | New leases begin a new fencing sequence |

Use build logs or another durable system as evidence. The store contains current state, not history.

#### Fencing tokens after restore

A wiped or rewound lease record can issue token 1 again. A protected resource that remembers a higher
token will reject new work until the sequence surpasses its high-water mark.

Recover by either:

- resetting the protected resource's high-water mark as part of the coordinated restore; or
- moving the work to a new piplex key and treating it as a new fencing domain.

Do not write a token directly into the exclusive key.

### Repair an unreadable value

Normal operator methods first parse the current value and therefore cannot replace malformed JSON.
Use the explicit repair methods:

```groovy
import io.github.green4j.piplex.Generation

piplex.designations()
      .repair('eod-owner', 'euc1-blue', 'INC-4821')
      .toCompletableFuture().join()
piplex.switches()
      .repair('eod-switch', true, null)
      .toCompletableFuture().join()
piplex.milestones()
      .repair('data/euc1', Generation.of('2026-09-14'))
      .toCompletableFuture().join()
```

Repair replaces an unreadable record with the supplied state. A repaired designation restarts its
sequence at 1; a milestone moves to exactly the supplied generation. Derive both from evidence.

Deleting a key is not equivalent to repair: an absent designation means nobody, while an absent switch
means enabled.

### What cannot be recovered

Piplex stores no history or authoritative audit trail. It cannot reconstruct:

- earlier designation or switch values;
- work already performed but never published;
- the previous fencing sequence after store loss;
- side effects made by a stale or interrupted holder.

Keep operational history in Jenkins and aggregated logs, and make protected work fenced or idempotent.

Previous: [7. discas and security](07-discas.md) · [Documentation index](README.md)

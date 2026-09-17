## 4. Switches

Switches are shared operational controls. They stop work without editing every controller's job
configuration and also revoke matching runs already in flight.

### Shared switch

An absent `piplex/enabled/<key>` means enabled. Disabling is the deliberate state:

```java
switches.disable("eod-switch", "INC-4821");
switches.enable("eod-switch");
```

A run with `enabledBy("eod-switch")` reads this switch before admission and watches it while admitted.
Disabling revokes it with `DISABLED`; the reason appears in its log.

Setting the current state again is a no-op. `SwitchChange` returns the previous and current records and
whether a write occurred.

### Drain one owner

The same request also reads a per-owner switch:

```text
piplex/enabled/<enabledBy>/@<ownerId>
```

To drain blue while other owners remain eligible:

```java
switches.disable(Switches.ownerKey("eod-switch", "euc1-blue"), "patching blue");
```

Re-enable it explicitly after maintenance. A request cannot use an owner-switch path as its
`enabledBy`, preventing one owner's control from becoming another work switch.

### Record and repair

The value is JSON:

```json
{"enabled":false,"reason":"INC-4821","at":"2026-09-15T02:40:00Z"}
```

Only `enabled` controls execution. The other fields explain the latest transition; they are not an
audit trail.

A malformed switch fails closed. Repair it only after deciding the intended state:

```java
switches.repair("eod-switch", true, null);
```

Writes retry compare-and-set up to eight times, then fail with `ContendedException`.

Previous: [3. Milestones](03-milestones.md) · Next: [5. Jenkins](05-jenkins.md) · [Documentation index](README.md)

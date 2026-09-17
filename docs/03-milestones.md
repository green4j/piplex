## 3. Milestones

A milestone records the highest generation a producer completed. Consumers wait for state, not for an
event, so they remain correct after missed changes and restarts.

### Publish

```java
milestones.publish("data/euc1", Generation.of("2026-09-14"),
        "euc1-blue", "eod#142");
```

Publishing is:

- **monotonic:** an earlier generation cannot replace a later one;
- **idempotent:** the current generation is a no-op;
- **version-fenced:** concurrent producers retry compare-and-set and cannot silently overwrite a
  later value.

Call publish only after successful work. A failed or interrupted producer must leave the milestone
unchanged.

`PublishResult` reports `PUBLISHED` or `ALREADY_AT_OR_AHEAD` and includes the record in force.

### Wait

```java
AwaitResult result = milestones.awaitAtLeast(
        "data/euc1", Generation.of("2026-09-14"), Duration.ofMinutes(90));
```

The waiter reads, compares, waits for a bounded change, then reads again. It returns:

- `REACHED` when the stored generation is at least the requested one;
- `TIMED_OUT` with the latest record, or `null` if nothing was published.

Cancelling the returned future stops the loop within its current one-minute round. A waiter holds no
state in the store.

### Ordering

Generations are strings ordered lexicographically. Prefer ISO-8601 dates or fixed-width counters.

Choose a key for a consumable result, not for a pipeline. If trades and positions can be consumed
independently, publish separate milestones; if only the combined day is valid, publish one after every
required branch succeeds.

When an exclusive request uses `completedWhen`, publish before releasing its lease. Use
`Admitted.completeAndRelease()` in core, put `piplexPublish` inside a scripted exclusive block, or use
the declarative whole-build pattern described in [Jenkins](05-jenkins.md#completion-order).

### Record and repair

`piplex/milestone/<key>` contains:

```json
{"generation":"2026-09-14","by":"euc1-blue","runId":"eod#142","at":"2026-09-15T02:37:12Z"}
```

Only `generation` affects decisions. The other fields explain the latest write.

Malformed records fail closed as `UnreadableKeyException`. An operator who knows the correct
generation may replace one with:

```java
milestones.repair("data/euc1", Generation.of("2026-09-14"));
```

Repair can move an unreadable milestone to any supplied generation, so derive it from durable evidence
such as build logs.

Previous: [2. Exclusive runs](02-exclusive-run.md) · Next: [4. Switches](04-switches.md) · [Documentation index](README.md)

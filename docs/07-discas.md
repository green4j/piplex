## 7. discas, ACLs and TLS

Piplex uses [discas](https://github.com/green4j/discas) as its production coordination store. This
chapter covers only the configuration relevant to piplex; discas documentation remains authoritative.

The plugin currently embeds discas client 0.0.5, and every cluster node must run the same version --
it is one number in the build, and the integration test runs a node at it. The adapter's floor is
0.0.4, where watches began reporting whether their deadline answer was confirmed and lock races began
distinguishing a free key from one held by an unnamed owner.

### Requirements

| Property | Requirement |
|---|---|
| Prefix | `piplex/<environment>/` |
| Operations | `GET` and `CAS` |
| Values | Small JSON and lock records |
| Writes | Usually a few per run or operator action |
| Reads | Linearizable reads and polling watches |

Lock acquire, renew and release are implemented by discas with reads and fenced compare-and-set.
Piplex does not use `PUT`, `DELETE` or `SCAN`.

### Three-node cluster

A three-node cluster survives one unavailable node:

```bash
discas-node \
  --node-id n1 \
  --cluster-id piplex-euc1 \
  --members n1=10.0.0.1:7001,n2=10.0.0.2:7001,n3=10.0.0.3:7001 \
  --client-bind 10.0.0.1:7101 \
  --wal-dir /var/lib/discas/n1
```

Peer ports in `--members` carry consensus traffic. Jenkins uses the separate client ports in
`--client-bind`:

```text
n1=10.0.0.1:7101,n2=10.0.0.2:7101,n3=10.0.0.3:7101
```

Use `--members-file` when membership must be reloaded. Health and metrics use
`--observability-bind`, default `127.0.0.1:9600`, with `/health`, `/ready` and `/metrics`.

Every option also has a `DISCAS_*` environment variable. Flags override environment values, which
override defaults.

### Environments and cluster ids

Two things separate work on a discas deployment, and they are not the same thing.

`--cluster-id` names a cluster. Nodes of one consensus group share it, and a client which connects
with the wrong one is talking to the wrong cluster. Separating environments this way means separate
nodes, separate WALs and separate quorums -- correct, and the right answer where the environments
must not share a failure domain, but it is a second deployment to run.

The `environment` setting names a segment of every key piplex writes, inside one cluster. It costs
nothing to run and ACLs can enforce it, and the trade is the one sharing always makes: a lost quorum
takes every environment on that cluster with it.

Use cluster ids where an outage must not be shared, and the environment segment where the cluster is
shared deliberately. Naming the cluster after the environment and stopping there separates nothing:
two controllers on one cluster still need the segment.

### Transport profiles

Who can reach the cluster at all. The companion axis, [Who writes what](#who-writes-what), decides
what a client that reached it may do.

#### Trusted private network

Use plaintext `allowall` only when network access itself is the trust boundary:

```bash
discas-node \
  --node-id n1 --cluster-id piplex-euc1 \
  --members n1=10.0.0.1:7001,n2=10.0.0.2:7001,n3=10.0.0.3:7001 \
  --client-bind 10.0.0.1:7101 \
  --wal-dir /var/lib/discas/n1 \
  --client-auth allowall
```

A client id is claimed, not authenticated. Restrict the client port with private binding, firewall or
security groups. Give each controller a distinct `clientId`; use ACLs if the cluster is shared.

#### Authenticated or cross-region

Protect peer traffic with mTLS and client traffic with either token-over-TLS or mTLS:

```bash
discas-node \
  --node-id n1 --cluster-id piplex-global \
  --members-file /etc/discas/members.conf \
  --client-bind 0.0.0.0:7101 \
  --wal-dir /var/lib/discas/n1 \
  --tls \
  --tls-keystore /etc/discas/tls/peer.p12 \
  --tls-truststore /etc/discas/tls/peer-ca.p12 \
  --client-auth mtls \
  --client-tls-keystore /etc/discas/tls/client-port.p12 \
  --client-tls-truststore /etc/discas/tls/client-ca.p12 \
  --client-acl-file /etc/discas/acl.conf \
  --audit-config-file /etc/discas/audit.conf
```

`--tls*` secures node-to-node traffic. `--client-*` secures the Jenkins-facing port.
`--client-auth mtls` implies client TLS.

Keep peer and client certificate authorities separate. Under mTLS, the client certificate CN is the
authoritative client id; issue each controller a certificate matching its configured `clientId`.

Supply PKCS12 passwords through environment variables such as
`DISCAS_TLS_KEYSTORE_PASSWORD` and `DISCAS_CLIENT_TLS_KEYSTORE_PASSWORD`, not command-line flags.
Certificate rotation is enabled by default and new files are loaded by `POST /reload`.

### ACLs

An ACL file contains one line per authenticated client:

```text
acl.<clientId> = <prefix>:<OPS> ; <prefix>:<OPS>
```

Piplex needs `G` (GET, including watches) and `C` (CAS, including leases):

```text
acl.piplex-euc1-blue  = piplex/prod/:GC
acl.piplex-euc1-green = piplex/prod/:GC
```

It does not need `P`, `D` or `S`.

Because the environment is a key prefix, one grant per environment is all the separation a shared
cluster needs: a controller granted `piplex/prod/:GC` cannot read or write uat's keys, whatever a job
on it names in a step. Grant `piplex/:GC` instead and that separation is gone -- which is the right
choice only where the environments are not a trust boundary.

An `activeWhenKey` lies outside `piplex/`, so grant `G` on it separately:

```text
acl.piplex-euc1-blue = piplex/prod/:GC ; /dc/active:G
```

The heartbeat key `piplex/<environment>/instances/<ownerId>` needs `GC` for duplicate-owner
detection, and it is the controller's own environment that has to be granted -- the heartbeat is
written there whatever environments its jobs name.

A request is allowed if some grant's prefix matches the key and carries the op; a longer prefix does
not override a shorter one, it adds to it. How far to narrow these grants is the subject of the next
section.

Authentication does not enable audit by itself. Configure `--audit-config-file` and reload it with
`POST /reload`. Audit records in `allowall` identify only a claimed client id.

### Who writes what

Three roles change piplex state, and they are not the same people, the same frequency or the same
risk:

| Role | Does | Typically |
|---|---|---|
| Work | Takes and renews leases, publishes the milestone it produced | The controller, unattended, every night |
| Operator | Designates an owner, stops work, drains a controller | A person, in an incident or a maintenance window |
| Admin | Changes the controller's piplex settings, and reads the store by hand where a question has no step | A person, rarely, holding ADMINISTER |

Only the first two reach discas as a client, so only they appear in an ACL file. The admin changes the
controller's own configuration, and for the two questions no step answers -- listing what keys exist,
inspecting a lock -- reads the store through a `discas-agent` sidecar. Both act as the controller, and
what restrains them is the ADMINISTER permission rather than a grant.

Note where the irreversible operation sits: publishing a milestone belongs to the operator, not to the
admin, because whoever knows the data really exists is usually not whoever holds ADMINISTER. What
guards it is the rights on that one job. Overwriting a record nothing can parse belongs there too, as
a parameter of the job that owns the key, because it is the same write to the same key by the same
identity -- see [6. A key holds something nothing can parse](06-operations.md#6-a-key-holds-something-nothing-can-parse).

Two decisions follow, and they are independent of each other.

#### Does the operator get its own identity?

**Sharing one.** The operator acts as the controller, and one line covers both roles:

```text
acl.piplex-euc1-blue  = piplex/prod/:GC ; /dc/active:G
acl.piplex-euc1-green = piplex/prod/:GC ; /dc/active:G
```

Nothing then stops a controller designating itself the owner or draining its neighbour. Where every
controller is run by the same people who would be doing the operating, that is not a threat, and this
costs nothing to maintain and needs no credential distributed.

**Separating them.** `C` on `designated/` and `enabled/` moves to an identity of its own, and the
controller keeps `G` on the guards it reads:

```text
acl.piplex-euc1-blue = piplex/prod/exclusive/eod:GC ; piplex/prod/milestone/data/euc1:GC ;
                       piplex/prod/instances/euc1-blue:GC ; piplex/prod/designated/eod-owner:G ;
                       piplex/prod/enabled/eod-switch:G ;
                       piplex/prod/enabled/eod-switch/@euc1-blue:GC ; /dc/active:G
acl.piplex-ops       = piplex/prod/designated/:GC ; piplex/prod/enabled/:GC
```

Operator jobs then name a credential carrying that identity:
[Operator credentials](05-jenkins.md#operator-credentials). Worth doing where controllers are run by
different teams, because it is cross-controller damage this prevents -- one team's controller
draining another's work.

Three details make that layout work.

The controller keeps `C` on **its own** per-owner switch key. Taking a controller out is done from
that controller, because whether work is still running there is a question about processes, and
requiring the operator identity for it would put that credential on every controller and undo the
separation. With this grant a controller drains itself and nobody else. It relies on grants adding up
rather than overriding, which is how discas evaluates them.

Because prefixes match raw text rather than path segments, `.../@euc1-blue` also matches
`.../@euc1-blue-2`. **No owner id may be a prefix of another**, or a controller can drain the one
whose name extends its own. Nothing checks this for you: listing `instances/` would need `S`, which
piplex does not ask for.

`milestone/` stays with the work role in both layouts. Publishing asserts that a particular controller
finished particular work, so it is never written by the operator identity -- including when an
operator publishes one to release a stuck consumer, which is therefore done from the producing
controller.

Separating identities is only as good as the transport under it. In `allowall` a client id is a claim
rather than a proof, so a second identity there separates nothing, and the plugin refuses to use one.
It means something only over token or mTLS, where the certificate CN is the authoritative client id.

#### How narrowly is each identity granted?

Independent of the above, and worth deciding for the work role whether or not the operator is
separate.

**A grant per environment** is one line and no upkeep:

```text
acl.piplex-euc1-blue = piplex/prod/:GC ; /dc/active:G
```

**A grant per key** bounds what a controller can damage to the work it actually runs:

```text
acl.piplex-euc1-blue = piplex/prod/exclusive/eod:GC ; piplex/prod/milestone/data/euc1:GC ;
                       piplex/prod/instances/euc1-blue:GC ; piplex/prod/designated/eod-owner:GC ;
                       piplex/prod/enabled/eod-switch:GC ; /dc/active:G
```

The two questions meet on those last two keys. `C` on `designated/` and `enabled/` is there because
the operator jobs run as the controller; where the operator has an identity of its own, it holds that
`C` and the controller keeps `G`, as the layout above has it. Granting only `G` in both places leaves
an estate whose handover, stop and drain are all refused -- the work runs and none of the operator
jobs do.

One reason matters more than the others. Any job on a controller can publish to any milestone key, and
a milestone only moves forward, so a wrong generation cannot be taken back **at all**: repair does not
help, because it replaces a record that cannot be parsed and leaves a readable one exactly as it found
it. What is left is to move on to a further generation, or to move the work to a new key. Naming the
milestone here bounds the mistake to what this controller produces, and no ACL can do better, because
every job on a controller shares its identity.

`C` on `enabled/eod-switch` also covers every per-owner key beneath it, because prefixes match raw
text -- which is what lets a controller drain itself in this layout without a second identity.

The cost is upkeep: every new work key is a regeneration and a `POST /reload`, and a grant left out
fails the work closed rather than silently.

#### Which combination to ask for

The ACL is generated from the estate, by `piplex-init` -- see [Deployment](09-deployment.md). The two
questions above are two of its answers, and these are the three combinations it writes:

| Operator identity | Grants | Ask for it when |
|---|---|---|
| Shared with the controller | Per environment | One team runs every controller |
| Shared with the controller | Per key | One team, and a wrong milestone would hurt |
| Its own | Per key | Controllers belong to different teams |

The fourth -- an identity of its own, granted per environment -- is refused. Grants add rather than
narrow, so a controller granted the whole environment keeps the very writes the second identity was
created to hold, and the separation exists only in the file.

Each of these three also exists as a file a person wrote and reviewed, in
`piplex-init/src/test/resources/acl/`. Nothing installs them: they are what the generator's output is
compared against, grant for grant, because a generator checked only against its own output is checked
against nothing.

### Jenkins TLS profiles

| Cluster mode | Jenkins fields |
|---|---|
| `allowall` | Nodes only |
| `token` | Token, TLS, trust store as needed; no client key store |
| `mtls` | TLS, client key store and trust store; no token |

The plugin refuses token without TLS, token together with a client certificate, TLS stores while TLS
is off, and disabling node-identity verification without a configured trust store.

With identity verification enabled, the node certificate must name one configured node id or host in
its subject alternative name, or in its common name only when no SAN exists. The check establishes
cluster membership, not a per-connection binding: a configured node certificate can authenticate any
configured endpoint because the discas TLS engine is not given the dialled peer name.

If identity verification is disabled, trust-store content becomes the whole server identity check.
Pin node certificates rather than a CA that also signs unrelated hosts.

TLS files are read when the store is built. Saving Jenkins configuration closes the current store and
rebuilds it on next use, so rotate when no guarded work is running.

### Rotating the token

Under `--client-auth token` the secret lives in two places, and both have to move together. The nodes
hold PBKDF2 hashes in the token store named by `--client-token-file` or `--client-token-dir`; each
controller holds the token itself in Manage Jenkins. Neither knows about the other, so a rotation that
changes one locks out every controller or leaves the old token working.

Add the new token before removing the old one, so the two overlap:

1. Add the new token's hash to the node token store, keeping the old entry, and `POST /reload` on
   every node. Both tokens now work.
2. Change the token field on each controller and save. Saving closes that controller's store and
   rebuilds it on next use, so do it when no guarded work is running on it -- one controller at a
   time, as a rolling change.
3. Remove the old hash from the token store and `POST /reload` again.

Between steps 2 and 3, `inspect` on each controller is what says it can still reach the cluster.
A controller left on the old token fails every read the moment step 3 lands, which is why step 3 is
last and separate.

The operator credential is not this token and rotates on its own -- see
[Jenkins](05-jenkins.md#operator-credentials).

### Manual wiring

The modules are not published to Maven; this example is for a host built alongside the repository:

```java
// Token profile: token plus a server-authenticated TLS provider.
// mTLS profile: no token and a provider carrying the client certificate.
DisCasClient client = DisCasClientFactory.create(
        ClientId.of("piplex-euc1-blue"),
        ClientDescription.of("piplex, controller euc1-blue"),
        new TcpClientBootstrap(nodes, ClientTransportConfig.defaults(), token, securityProvider),
        DisCasClientConfig.defaults());

CoordinationStore store = new DiscasCoordinationStore(
        client, ReadConsistency.LINEARIZABLE, Duration.ofSeconds(30), true);
Piplex piplex = new Piplex(store, TimeSource.of(scheduler), observer);
```

`LINEARIZABLE` is required for watches whose results revoke admitted work. The optional poll period
affects only `Watch.BACKGROUND`; urgent holder watches retain the client period.

Store calls are bounded by `responseBound`, five minutes by default. The discas client defaults to a
30-second request deadline. One adapter operation may use up to four client requests, so the default
bound also covers a client deadline of up to 75 seconds.

### Production checklist

- Three nodes with separate durable WAL directories.
- Client port private or mutually authenticated.
- Distinct controller `ownerId`, `clientId` and, under mTLS, matching certificate CN.
- No `ownerId` is a prefix of another, where a per-owner grant narrows by one.
- A separate operator identity only over token or mTLS, never over `allowall`.
- Token with TLS and no client key store, or mTLS with no token.
- Node identity verification enabled, or node certificates explicitly pinned.
- ACL grants include `piplex/<environment>/instances/` when narrowed by prefix.
- One ACL grant per environment where a cluster is shared and the environments are a trust boundary.
- ACL grants include `G` on every `activeWhenKey`.
- Client and nodes run exactly the same discas version.
- Certificate, membership and ACL reload is automated.
- `/health`, `/ready` and `/metrics` are monitored.

Previous: [6. Operations](06-operations.md) · Next: [8. Lifecycle](08-lifecycle.md) · [Documentation index](README.md)

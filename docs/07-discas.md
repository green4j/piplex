## 7. The discas cluster, ACLs and TLS

piplex keeps its four keys in a [discas](https://github.com/green4j/discas) cluster. This page is what
you need to stand that cluster up, decide how much security it needs, and connect Jenkins to it.

discas' own documentation is the authority on discas. What is here is the piplex-shaped subset: which
operations piplex uses, what to grant it, and what the Jenkins plugin can and cannot do today.

Everything below is for **discas 0.0.2**, which is the floor for piplex.

### What piplex needs from the cluster

Very little, and that is worth knowing before sizing anything.

| | |
|---|---|
| Key prefix | `piplex/` -- nothing outside it is ever touched |
| Keys | four per `<key>`: `designated/`, `enabled/`, `milestone/`, `exclusive/` |
| Operations | **get** and **compare-and-set**. Nothing else |
| Value sizes | small JSON records, a few hundred bytes |
| Write rate | a handful of writes a day |
| Read rate | one watch poll per parked candidate per key, per poll period |

The leases are the part worth spelling out. A discas lock is **not** a separate wire operation: the
client implements `tryLock`, `renewLock` and `release` over a get and a fenced compare-and-set on the
same key. So a lease needs no permission that a milestone does not.

piplex never uses `PUT`, `DELETE` or `SCAN`. It never writes an empty value, which is what would make a
CAS count as a delete.

### Running a cluster

Three nodes is the usual shape. A quorum of two survives one node going away.

```bash
discas-node \
  --node-id n1 \
  --cluster-id piplex-euc1 \
  --members n1=10.0.0.1:7001,n2=10.0.0.2:7001,n3=10.0.0.3:7001 \
  --client-bind 10.0.0.1:7101 \
  --wal-dir /var/lib/discas/n1
```

Two ports per node, and they are different things:

| Port | Who connects | Set by |
|---|---|---|
| `7001` in `--members` | the other nodes (consensus) | `--members` / `--members-file`, `--peer-bind` |
| `7101` in `--client-bind` | Jenkins controllers | `--client-bind` |

The `nodes` field in the Jenkins configuration lists the **client** ports:

```
n1=10.0.0.1:7101, n2=10.0.0.2:7101, n3=10.0.0.3:7101
```

Every option also has a `DISCAS_*` environment variable (`DISCAS_NODE_ID`, `DISCAS_CLIENT_BIND`, and so
on). A flag beats the environment variable, which beats the default. That is what makes the same image
deployable from a unit file, a container environment or a Helm values file without a wrapper script.

Use `--members-file` instead of `--members` where membership changes: the file is `node.<id>=host:port`
and is re-read on `POST /reload`, without a restart.

Health and metrics are served on `--observability-bind`, default `127.0.0.1:9600`, with `/health`,
`/ready` and `/metrics`. It binds to loopback by default because it exposes peer and topology detail.

### Choosing a security profile

There are two shapes worth deploying, and the choice is not about taste. It is about whether the network
between the controllers and the nodes is one you already trust.

| | A. Fully trusted environment | B. Cross-region |
|---|---|---|
| When | one datacentre, one private network, one team | controllers or nodes on different networks |
| Peer transport | plaintext | **mTLS** (`--tls`) |
| Client transport | plaintext | **mTLS** (`--client-auth mtls`) |
| Client identity | claimed, taken at face value | the client certificate's CN |
| ACLs | optional | **yes** |
| Audit | optional | yes |
| Set on the controller | nothing | token and/or TLS stores -- see [connecting Jenkins](#connecting-jenkins-to-it) |

### Profile A: a fully trusted environment

This is the default, and it is what the [Quick start](00-quick-start.md) assumes.

```bash
discas-node \
  --node-id n1 --cluster-id piplex-euc1 \
  --members n1=10.0.0.1:7001,n2=10.0.0.2:7001,n3=10.0.0.3:7001 \
  --client-bind 10.0.0.1:7101 \
  --wal-dir /var/lib/discas/n1 \
  --client-auth allowall
```

`allowall` is the default and needs no flag. Be clear about what it means:

**A client id is claimed, not checked.** A controller that connects as `piplex-euc1-blue` is
`piplex-euc1-blue` because it said so. Nothing verifies it, and anything that can reach the client port
can claim any id and write any key.

So in this profile, the network **is** the access control. Bind the client port to a private interface,
keep it behind a security group or firewall that lists the controllers, and do not put it on a network
you would not give a shell on.

Two things are worth doing even here:

- **Give every controller its own `clientId`.** It costs nothing, and it is what makes a connection list
  on the cluster readable. `piplex-euc1-blue`, not `jenkins`.
- **Add ACLs anyway if the cluster is shared** with anything that is not piplex. They are cheap, and
  they mean a bug in something else cannot write `piplex/designated/eod`.

Any record of who did what in this mode is worth exactly as much as the claim behind it. Do not treat it
as evidence.

### Profile B: cross-region, mTLS for every actor

Here nothing is taken at face value. Every peer link and every client link is mutually authenticated,
and identity comes from a certificate rather than from a string in a hello message.

#### The node

```bash
discas-node \
  --node-id n1 --cluster-id piplex-global \
  --members-file /etc/discas/members.conf \
  --client-bind 0.0.0.0:7101 \
  --wal-dir /var/lib/discas/n1 \
  \
  --tls \
  --tls-keystore /etc/discas/tls/peer.p12 \
  --tls-keystore-password "$DISCAS_TLS_KEYSTORE_PASSWORD" \
  --tls-truststore /etc/discas/tls/peer-ca.p12 \
  --tls-truststore-password "$DISCAS_TLS_TRUSTSTORE_PASSWORD" \
  --tls-cert-rotation \
  \
  --client-auth mtls \
  --client-tls-keystore /etc/discas/tls/client-port.p12 \
  --client-tls-keystore-password "$DISCAS_CLIENT_TLS_KEYSTORE_PASSWORD" \
  --client-tls-truststore /etc/discas/tls/client-ca.p12 \
  --client-tls-truststore-password "$DISCAS_CLIENT_TLS_TRUSTSTORE_PASSWORD" \
  --client-tls-cert-rotation \
  \
  --client-acl-file /etc/discas/acl.conf \
  --audit-config-file /etc/discas/audit.conf
```

What each group does:

| Flags | What they secure |
|---|---|
| `--tls`, `--tls-keystore`, `--tls-truststore` | the **peer** transport: node to node consensus traffic |
| `--client-auth mtls`, `--client-tls-*` | the **client** port: controllers and operators |
| `--client-acl-file` | what an authenticated client may do, per key prefix |
| `--audit-config-file` | what gets recorded. Without it, nothing is |

Three details that are easy to get wrong:

**`--client-auth mtls` implies `--client-tls`.** You do not pass both. The client-port truststore is
required, because that is the CA whose signatures make a client certificate acceptable.

**Under mtls, the certificate's CN is the authoritative client id**, not the id the client sends. Issue
each controller a certificate whose CN is its `clientId` -- `piplex-euc1-blue` -- and the two agree by
construction. A mismatch is not something to paper over: the point of this profile is that the id in the
hello message stops being a claim.

**The peer store and the client store are separate on purpose.** Nodes trust each other through one CA;
controllers are admitted through another. One CA for both means a controller's certificate is also good
enough to join the cluster as a node.

Passwords come from the environment (`DISCAS_TLS_KEYSTORE_PASSWORD` and friends) so that a process list
does not carry them.

#### Certificate rotation

`--tls-cert-rotation` and `--client-tls-cert-rotation` are on by default. A rotated keystore on disk is
picked up on `POST /reload`, without a restart and without dropping the cluster.

That is the difference between a certificate expiry being a scheduled file copy and being an outage, so
leave them on and wire the reload into whatever renews the certificates.

### ACLs

`--client-acl-file` names a file of grants, re-read on `POST /reload`.

**Without the file, every authenticated client may access every key.** Authentication and authorization
are separate decisions here, and the second one is opt-in.

The format is one line per client:

```
acl.<clientId> = <prefix>:<OPS> ; <prefix>:<OPS> ; ...
```

The ops are single letters:

| Letter | Operation |
|---|---|
| `G` | GET -- reads, and the watches built on them |
| `P` | PUT |
| `C` | CAS -- compare-and-set, including every lease operation |
| `D` | DELETE -- also required for a CAS that writes an empty value |
| `S` | SCAN |

#### What piplex needs

`G` and `C` on the `piplex/` prefix. That is all, and it is worth granting exactly that:

```
# Jenkins controllers: read and compare-and-set, under piplex/ only.
acl.piplex-euc1-blue  = piplex/:GC
acl.piplex-euc1-green = piplex/:GC
acl.piplex-apac1      = piplex/:GC
```

`C` covers the leases, because a lease is a fenced compare-and-set on `piplex/exclusive/<key>`. `D` is
not needed: piplex never writes an empty value. `P` and `S` are never used at all.

#### Narrowing further

A prefix is a prefix, so a controller can be confined to the work it is allowed to touch:

```
# euc1 controllers cannot reach apac1's keys at all.
acl.piplex-euc1-blue = piplex/designated/eod:G ; piplex/enabled/eod:G ; piplex/milestone/data/euc1:GC ; piplex/exclusive/eod:GC
```

Note what that line says, and it is the interesting part. A controller needs only **`G`** on
`designated/` and `enabled/`: it reads those keys and watches them, and it never writes them. Writing
them is the operator's job.

So the operator's client gets the grant the controllers do not have:

```
# The job that runs designate() and disable().
acl.piplex-ops = piplex/designated/:GC ; piplex/enabled/:GC
```

Now "who may hand the work to another region" is a property of the cluster, not of who happens to have
the Script Console open. That separation is the main reason to write an ACL file at all.

#### Audit

`--audit-config-file` turns on recording, `audit.<name>=<value>`, re-read on `POST /reload`. Without it
nothing is recorded.

It is worth turning on in profile B and not worth much in profile A: a record naming a client id that
nothing verified records a claim. Its buffer comes out of the same heap budget as the store, so size it
deliberately.

### Connecting Jenkins to it

**Manage Jenkins > System > piplex** has the settings for all three modes. They line up with the
node's flags one for one:

| Cluster `--client-auth` | Fill in on the controller |
|---|---|
| `allowall` | nothing beyond the nodes |
| `token` | **Shared token**, and tick **Connect over TLS** so it does not cross the wire in clear |
| `mtls` | tick **Connect over TLS**, then **Key store** and **Trust store** |

| Field | What it is |
|---|---|
| Shared token | the token the cluster's `--client-token-file` lists for this client id. Stored encrypted, shown as a password field |
| Connect over TLS | whether the connection is TLS at all. Nothing else here takes effect without it |
| Trust store | PKCS12 holding the CA that signed the nodes' **client-port** certificates. Blank falls back to the JVM's own trust store, which is right only if a public CA signed them |
| Trust store password | usually blank: a store of public certificates needs none |
| Key store | PKCS12 holding **this controller's** client certificate and key. Required under `mtls` |
| Key store password | opens the store, and is taken as the private key's password too |

Configuration as Code reaches them the same way it reaches the rest:

```yaml
unclassified:
  piplex:
    ownerId: "euc1-blue"
    clientId: "piplex-euc1-blue"
    nodes: "n1=10.0.0.1:7101, n2=10.0.0.2:7101, n3=10.0.0.3:7101"
    tls: true
    tlsTruststore: "/etc/discas/tls/client-ca.p12"
    tlsKeystore: "/etc/discas/tls/euc1-blue.p12"
    tlsKeystorePassword: "${PIPLEX_KEYSTORE_PASSWORD}"
```

The token and both passwords are Jenkins `Secret` values: encrypted in
`io.github.green4j.piplex.jenkins.PiplexConfiguration.xml`, never rendered back into the form, and
resolvable from a CasC variable as above rather than written into the YAML.

Four things worth knowing before you roll this out.

**TLS is a checkbox, not something inferred.** A key store filled in with the box unticked is refused,
by name, rather than quietly ignored:

```
piplex: a TLS key store or trust store is configured but TLS is off. Tick 'Connect over TLS' in
Manage Jenkins > System, or clear the stores
```

Inferring it the other way is how a cleared field silently downgrades every controller to plaintext
with nothing saying so.

**Under `mtls` the certificate decides the client id, not the form.** The **discas client id** field
still names the connection for the plugin's own purposes, but the node reads the certificate's subject.
Issue each controller a certificate whose CN is the client id you typed, and the two agree. Where they
disagree, the certificate wins and your ACL lines have to match it.

**The stores are files on the controller**, read fresh every time the store is built. So a rotated
certificate is picked up by saving the configuration -- which closes and rebuilds the store anyway --
and not only by a restart. The files must be readable by the Jenkins process and nothing else; `0400`,
owned by the Jenkins user.

**Changing any of these closes the store**, exactly as `ownerId` and `nodes` do, and a run in flight is
revoked once its grace period is out. Rotate when nothing is running. See
[the plugin](05-jenkins.md#changing-a-setting-closes-the-store).

#### What it does not expose

One client setting the plugin fixes rather than offers: the `ReadConsistency` of watches, pinned to
`LINEARIZABLE`, for a reason worth reading before wishing it were a field --
[why it is not a setting](06-operations.md#why-watches-read-linearizable-and-why-that-is-not-a-setting).
The cost knob that *is* a field is the watch poll period. TLS protocol and cipher-suite lists are
discas' defaults, and there is no field for those either.

### Wiring it by hand, outside Jenkins

Outside Jenkins there is no form, and the same thing is a constructor call. This is also where
`ReadConsistency`, the one knob the plugin fixes, is yours to set:

```java
// Profile B, from your own code: a token, a TLS provider, or both.
DisCasClient client = DisCasClientFactory.create(
        ClientId.of("piplex-euc1-blue"),
        ClientDescription.of("piplex, controller euc1-blue"),
        new TcpClientBootstrap(nodes, ClientTransportConfig.defaults(), token, securityProvider),
        DisCasClientConfig.defaults());

CoordinationStore store = new DiscasCoordinationStore(
        client, ReadConsistency.LINEARIZABLE, Duration.ofSeconds(30), true);
Piplex piplex = new Piplex(store, TimeSource.of(scheduler), observer);
```

The store owns the client from there: closing one closes the other.

`securityProvider` is what the plugin builds from its own TLS settings: a
`TlsClientSecurityProvider` over an `SSLContext`, or `PlaintextClientSecurity.PROVIDER` for none.

`piplex-example` has a runnable version against a real cluster
([`DiscasStoreExample`](../piplex-example/src/main/java/io/github/green4j/piplex/example/DiscasStoreExample.java)),
in the plaintext shape.

### A checklist

Before a cluster carries anything you care about:

- [ ] three nodes, each with its own `--wal-dir` on durable storage
- [ ] `--client-bind` on a private interface, not `0.0.0.0`, unless the client port is mutually authenticated
- [ ] every controller has its own `clientId`, and it matches its certificate CN under mtls
- [ ] the controllers' TLS settings match the node's mode, and the store files are readable only by the Jenkins user
- [ ] `--client-acl-file` written, granting `piplex/:GC` to controllers and `piplex/designated/`, `piplex/enabled/` writes only to the operator client
- [ ] client and nodes on the **same** discas version -- see [operations](06-operations.md#version-skew)
- [ ] `POST /reload` reachable from whatever rotates certificates and edits ACLs
- [ ] `/health` and `/ready` scraped

---

Previous: [6. Operating piplex](06-operations.md) &middot; Back to [the index](README.md)

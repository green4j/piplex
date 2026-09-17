## 7. discas, ACLs and TLS

Piplex uses [discas](https://github.com/green4j/discas) as its production coordination store. This
chapter covers only the configuration relevant to piplex; discas documentation remains authoritative.

The plugin currently embeds discas client 0.0.4. Every cluster node must run the same version. This
release is required because watches report whether their deadline answer was confirmed and lock races
distinguish a free key from one held by an unnamed owner.

### Requirements

| Property | Requirement |
|---|---|
| Prefix | `piplex/` |
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

### Security profiles

#### A. Trusted private network

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

#### B. Authenticated or cross-region

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
acl.piplex-euc1-blue  = piplex/:GC
acl.piplex-euc1-green = piplex/:GC
```

It does not need `P`, `D` or `S`.

To separate operational writes from controllers, grant the keys listed in
[Stored keys](01-model.md#stored-keys):

```text
acl.piplex-euc1-blue = piplex/designated/eod-owner:G ; piplex/enabled/eod-switch:G ; piplex/milestone/data/euc1:GC ; piplex/exclusive/eod:GC ; piplex/instances/euc1-blue:GC
acl.piplex-ops = piplex/designated/:GC ; piplex/enabled/:GC
```

The heartbeat grant is required for duplicate-owner detection. Under this split, designation and
switch procedures cannot use the controller's Script Console because it connects as the controller;
run them through the separately authenticated operations client. If that separation is unnecessary,
grant controllers `piplex/:GC`.

Authentication does not enable audit by itself. Configure `--audit-config-file` and reload it with
`POST /reload`. Audit records in `allowall` identify only a claimed client id.

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
- Token with TLS and no client key store, or mTLS with no token.
- Node identity verification enabled, or node certificates explicitly pinned.
- ACL grants include `piplex/instances/` when narrowed by prefix.
- Client and nodes run exactly the same discas version.
- Certificate, membership and ACL reload is automated.
- `/health`, `/ready` and `/metrics` are monitored.

Previous: [6. Operations](06-operations.md) · Next: [8. Lifecycle](08-lifecycle.md) · [Documentation index](README.md)

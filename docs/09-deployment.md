## 9. Deploying piplex

The order to do things in, once. Each step links to the chapter that explains it rather than
repeating it, because a second copy of the TLS rules is a second copy to keep true.

[Quick start](00-quick-start.md) is the version that teaches; this is the version to follow when it
matters.

### What is generated, and why

Four artifacts have to name the same keys: the cluster's ACL, each controller's settings, the five
operator jobs, and the `piplexExclusive` block in each guarded pipeline. Kept by hand they are four
places to keep in step, and they stop being in step silently -- a grant fails a write that nobody
makes until the night somebody needs it.

So they are not kept by hand. `piplex-init`, in the release archive, takes one description of the
estate and writes all four, composing every key with the same code the plugin writes it with:

```text
./piplex-init                                             # ask, then write into ./piplex
./piplex-init --from piplex/estate.properties             # ask again from what you decided
./piplex-init --from piplex/estate.properties --quiet     # regenerate, ask nothing
```

Use `piplex-init.cmd` on Windows. The launchers use `JAVA_HOME` when it is set and otherwise find
Java on `PATH`; `java -jar piplex-init.jar` is the direct equivalent.

`estate.properties` is the only file anybody edits. Everything else carries a line saying it was
generated and where from: edit one of those and the set stops agreeing, which is the problem the tool
exists to remove.

The output directory belongs to the generator. A run writes a complete sibling tree and swaps it into
place only when every file is ready; controllers and pipelines removed from the description disappear
with the old tree. A non-empty directory that is not already a `piplex-init` tree is refused. Keep
hand-written files beside it, and put the directory inside a repository rather than putting a
repository inside the directory.

The sections below are the decisions the dialogue asks about. Answer them first, then run it.

### 1. Stand up the cluster

Three discas nodes with separate durable WAL directories, and Jenkins talking to the **client** ports
rather than the peer ports. See [Three-node cluster](07-discas.md#three-node-cluster).

Decide `--cluster-id` and the `environment` segment now, because changing either later moves every
key: [Environments and cluster ids](07-discas.md#environments-and-cluster-ids).

### 2. Choose a transport profile

Who can reach the cluster: a trusted private network, or token and mTLS.
See [Transport profiles](07-discas.md#transport-profiles).

This decision limits the next one. Giving the operator an identity of its own is only meaningful over
token or mTLS, because in `allowall` a client id is a claim rather than a proof.

Under token or mTLS the dialogue also asks where the key material lives. It asks for a **path**: no
generated file holds a secret, pass phrases stay Configuration as Code variables, and the `RUN.md` it
writes says how to produce the files if you have none.

### 3. Answer two questions about the ACL

What a client that reached the cluster may do: [Who writes what](07-discas.md#who-writes-what). Two
questions, independent of each other.

**[Does the operator get its own identity?](07-discas.md#does-the-operator-get-its-own-identity)**
Give it one when an operator write must not be possible under a controller's identity -- a controller
designating itself the owner, or draining its neighbour. Where the controllers belong to the team that
would be doing the operating, that is not a threat worth a credential to distribute.

**[How narrowly is each identity granted?](07-discas.md#how-narrowly-is-each-identity-granted)** A
grant per environment is one line and no upkeep. Granting key by key stops a controller writing work
it does not run, and costs a regeneration and a `POST /reload` per new key.

The fourth combination -- an operator apart, granted per environment -- is refused, and the reason is
worth knowing: grants add rather than narrow, so a controller granted the whole environment keeps the
very writes the operator was separated to hold, and the separation exists only in the file.

The three combinations these answers produce are in
[Which combination to ask for](07-discas.md#which-combination-to-ask-for), which is also where the
reasoning behind each grant is written down.

### 4. Describe the work

One entry per piece of work: its key, the switch it consults, who decides where it runs, and the
milestone it publishes if it publishes one. These four travel together -- the operator jobs read them
as a set -- which is why the jobs offer a choice of work rather than four fields to fill in.

Two answers here are worth thinking about before typing.

**A switch belongs to a business process, not to a pipeline.** Stopping it is one decision with one
radius; see [stopping the work](06-operations.md#4-stop-the-work-everywhere-and-resume-it). Work that
names no switch is reported, because a drain reaches only work that names one, and that work keeps
running on a controller somebody has been told is empty.

**Who decides where it runs is one answer, not two.** Piplex's own designation, or a key something
else writes. A request carrying both is refused, and so is an estate that names both.

### 5. Run it, and check what it says

The dialogue prints the estate's problems every round. The final gate checks them again before making
an output directory: if any remain, nothing is written. A non-interactive or `--quiet` run exits 2,
so deployment automation cannot mistake a refused estate for generated artifacts.

Every problem has to be resolved: two controllers answering to one name, an owner id that is a prefix
of another (grants match raw text, not path segments), an operations identity with no credential named
to carry it. Controller owner ids and work keys also have to be one file name: absolute names, `.`,
`..` and path separators are refused because those names become directories and pipeline files in the
generated tree.

### 6. Install what it wrote

`RUN.md`, written beside the rest, is the per-estate version of this. In outline:

| What | Where |
|---|---|
| `acl/piplex.conf` | every node's `--client-acl-file`, then `POST /reload` |
| `controllers/<ownerId>/jenkins.yaml` | that controller's Configuration as Code |
| `controllers/<ownerId>/jobs/` | five pipeline jobs, in one folder |
| `pipelines/<work>.groovy` | pasted into that work's pipeline, or fed to whatever generates it |

One directory per controller, because the two fields that tell controllers apart are in them. Copied
between machines, two controllers answer to one name and which of them runs the work is chance.

Then run one build that reaches the store. The settings page does not test connectivity, and a wrong
port or a version mismatch shows up here rather than at two in the morning.

#### Where the operator jobs live

One invariant and one choice.

**The drain job goes on every work controller.** It waits for work still running on the controller it
is taking out, which is a question about processes on that machine, so it can only be answered there.
This is not a preference, and it is why the generated `drain-controller` carries no credential
parameters even where the operator has an identity of its own.

The other four are placed:

| | For | Against |
|---|---|---|
| On one work controller | One folder, one set of rights, the credential in one place | If that controller is unavailable there is no handover -- and it being unavailable is sometimes the plan |
| On every work controller | Handover and stop work from whichever controller is up | N copies to keep level, and upgrades go one controller at a time; where the operator has its own identity, the credential is on each |

There is no separate operator controller in this arrangement. Self-drain means the one job that must
be everywhere needs no operations credential, so "the credential in one place" is already available
from an ordinary work controller -- and a second Jenkins would be another install to keep on a
matching plugin and discas version.

#### The operations credential

Only where the operator has an identity of its own. Skip it otherwise: the jobs are then generated to
act as the controller, which is what the other two ACL layouts intend.

Put the credential in the **job folder's** store, not the global one, or every job on the controller
can act as the operator. See [Operator credentials](05-jenkins.md#operator-credentials), which is also
where the rotation procedure and the four places the identity's name appears are written down.

### 7. What the jobs are, and why there are five

| Job | What it is for | Who should be able to run it |
|---|---|---|
| `inspect.groovy` | Why has tonight's round not run | Everyone. It reads, and reading grants nothing |
| `handover.groovy` | Move a piece of work to another owner | Whoever decides where work runs |
| `drain-controller.groovy` | Take this controller out, and put it back | Whoever patches controllers |
| `stop-work.groovy` | Halt a business process everywhere, and resume | Whoever may stop one |
| `announce-completion.groovy` | Announce a completion that was lost | Whoever knows the data exists |

They are separate jobs because Jenkins grants rights per job, and these are not the same rights.
Merged into one job with an action parameter, "may patch a controller" and "may stop tonight's run"
become the same permission.

Each carries the estate in a generated block at the top and takes only what the operator decides
tonight as parameters: which work, which switch, which owner, and why. A parameter naming a key the
job was not generated with is refused, because the choice constrains the form and not a build started
through the API.

An ungenerated template refuses outright and writes nothing. That is what an unpacked archive does
when somebody presses Build on it: the alternative is a plausible sample name, which is a real key on
somebody's cluster.

There is deliberately no sixth job for repairing a key. Repairing one is the same write as the
ordinary operation on it -- same key, same identity, same rights -- differing only in that a value
nothing can parse is overwritten rather than refused. So it is the `OVERWRITE_UNREADABLE` parameter of
the four jobs above, off by default, and not a job with its own permissions to grant. See
[6. A key holds something nothing can parse](06-operations.md#6-a-key-holds-something-nothing-can-parse).

`inspect.groovy` has no such parameter, and this is the reason: it is the one job everyone may run,
and a write parameter on it would make "may diagnose" and "may overwrite shared state" one permission.
It names the job and the parameters to use instead.

### 8. Check it before you need it

In order, and on a day when nothing is wrong:

1. Run `inspect`. It should describe the work truthfully.
2. Run `stop-work` with `stop`, confirm a guarded build skips, then `resume`.
3. Run `drain-controller` on one controller while work is running there. It must wait, not
   return at once.
4. Run `handover` to the other controller and back. Its Confirm stage reads every guard the
   work names; a yellow build means the designation landed and the work still cannot run there.
5. Where the operator has its own identity, run an operator job from a job **outside** the
   credential's folder. It must fail: that failure is the access control working.

The [production checklist](07-discas.md#production-checklist) is the standing version of this.

Previous: [8. Lifecycle](08-lifecycle.md) · [Documentation index](README.md)

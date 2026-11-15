# Top 100 candidate concurrency classes from other benchmarks

## Recommendation and scope

Start with **HierarchicalScheduler, ThermostaticBuildOrchestrator,
ResolvingVendingMachine, HarmonicBufferedStream, UserSpaceProvisioner,
VersionedSlotManager, TaggedCapacityBuffer, and AdaptiveTrustVault**. These have
more interesting concurrency requirements than merely synchronizing a collection:
cross-object invariants, slow work outside locks, cancellation, callbacks,
conditional commits, and coupled state transitions.

I inventoried all **300 records in `data.json`, 100 in
`ClassEval_data.json`, and 43 prompts in `problemlist.csv`**, then examined the
contracts of the shortlisted problems. This report contains **100 ranked
candidates: 71 from `data.json`, 23 from `ClassEval_data.json`, and 6 from
`problemlist.csv`**. The list is ordered approximately by expected benchmark
value, considering concurrency difficulty, domain-specific invariants, and
opportunities for static analysis. The first 21 are detailed starting points;
22-74 expand the difficult domain adaptations; 75-100 are secondary choices,
simpler variants, or focused controls. This is a qualitative shortlist, not an
exhaustive ranking or a claim that all 100 concepts are new in the concurrency
literature.

**Source convention:** JSON references point to the physical line containing the
entire escaped `skeleton` string, not a line in its decoded Python code.
`ClassEval_...` identifies a record within the named file; the two JSON files
reuse IDs for different classes, so an ID alone is insufficient. CSV references
cover the complete multiline problem record.

**Original versus proposed:** Each entry distinguishes the existing problem from
my proposed Java concurrency adaptation. Blocking operations, fairness rules,
transaction IDs, generations, and cancellation are additions unless explicitly
described as existing behavior. These are design sketches for future prompts,
not implemented classes or experimentally confirmed analyzer findings.

The **top twenty ranked entries** have now been adapted into explicit Java prompt
contracts in [`pipeline/prompt_generator/novel_problems.py`](pipeline/prompt_generator/novel_problems.py).
The prompt generator emits them as `novel_problems` alongside the existing
`datastructures` category. The runner stores implementations separately under
`Generations/date:*/client/model/{datastructures|novel_problems}/`; each category
is annotated and evaluated independently. The implemented prompt contracts
choose concrete policies from the design alternatives below; they are not
reference implementations or evidence of analyzer effectiveness.

## Important constraints for static-analysis evaluation

- **Use visible, inspectable shared state.** Private instance fields, arrays,
  linked records, and explicit `synchronized` blocks fit the current
  [`base_prompt.py`](pipeline/prompt_generator/base_prompt.py). Represent external work using
  small interfaces defined in the same Java file. For example, a provisioner
  can invoke an injected installer instead of requiring real filesystem
  operations. Do not translate Python libraries, RPM tooling, SQLite, or NumPy
  literally into the benchmark.
- **Do not equate different kinds of escaping.** Exposing a mutable array or
  record through an accessor is representation escape; registering a listener
  or starting work before construction finishes is `this` escape; publishing
  a finished object without a happens-before relationship is unsafe publication.
  Include all three as separate targets where appropriate.
- **The local CodeQL queries have important eligibility limits.**
  [`Escaping.ql`](pipeline/analysis/codeql/queries/Escaping.ql) selects non-private,
  non-final, non-volatile fields of `ClassAnnotatedAsThreadSafe` classes. It does
  not establish that a private mutable object returned by a getter is safe, and
  its exclusion of final fields does not make final arrays immutable.
  [`ThreadSafe.ql`](pipeline/analysis/codeql/queries/ThreadSafe.ql) and
  [`SafePublication.ql`](pipeline/analysis/codeql/queries/SafePublication.ql) also depend
  on that class classification. The publication query is a field-modifier/
  initialization heuristic, not proof of safe construction or publication.
  Resolve the `ConflictingAccess` import and verify class eligibility in the
  installed query environment before comparing results. The current generator
  does not request a thread-safety annotation, and the CSV prompts explicitly
  prohibit annotations.
- **Infer is configured for RacerD and starvation**, as shown in
  [`racerd.py`](pipeline/analysis/infer/racerd.py). Racy accesses, inconsistent locking,
  and modeled blocking call chains are plausible analysis targets. Long CPU
  computations, arbitrary injected callbacks, missed notifications, and
  application-specific fairness may not be recognized. Calibrate the exact
  checker configuration rather than assuming every listed defect is detectable.
- **Safety and progress need different oracles.** A clean static report is not
  proof of linearizability, bounded waiting, absence of missed wakeups, or
  eventual service. Add functional/interleaving checks for those properties.
  Intrinsic monitors and `notifyAll()` do not guarantee FIFO admission: any
  fairness requirement needs an explicit admission policy, and progress claims
  must assume eligible threads are eventually scheduled and external work
  eventually returns.

For useful experimental comparisons, build a correct reference and small,
single-defect variants of each selected adaptation. Measure which defects each
analyzer actually reports; distinguish unsupported checks from missed findings.
The existing CodeQL database builder creates databases, but database creation
alone does not execute these queries.

## Ranked candidate problems

Each entry gives a source problem, proposed implementation details, bug
opportunities, and a behavioral check. The implementation sketches are examples,
not mandatory solutions. Similar domains only justify separate entries when
the proposed invariant or failure scenario differs; they should not be treated
as independent evidence of conceptual novelty.

### 1. HierarchicalScheduler -> concurrent hierarchical reservation tree

**Source:** [`data.json:3549`](prompts_from_other_benchmarks/data.json#L3549),
`ClassEval_228`.

**Existing problem:** Nodes have parents, children, and reservations. Reserving
a node checks its own schedule and both ancestor and descendant schedules.
Child resources are created dynamically.

**Proposed concurrency implementation:** Expose `child(name)`,
`reserve(start, end)`, `cancel(id)`, and `snapshot()`. A parent reservation
excludes overlapping reservations anywhere below it, while siblings can hold
independent reservations. Child lookup/creation must publish exactly one fully
initialized child per name. A shared tree coordinator monitor is a simple
correct baseline; a harder variant can use a documented ordered multi-lock
protocol. An optional blocking API should prevent repeated child admissions
from indefinitely overtaking an eligible waiting parent request.

**Bug opportunities:** Upward/downward lock-order inversion, check-then-reserve
races across nodes, duplicate or partially published children, mutable
reservation escape, and parent starvation.

**Discriminating check:** Race a reservation on a parent against an overlapping
reservation on its child. Exactly one may commit. Concurrent requests for the
same child must return the same node.

### 2. ThermostaticBuildOrchestrator -> hysteretic, adaptive work admission

**Source:** [`data.json:2220`](prompts_from_other_benchmarks/data.json#L2220),
`ClassEval_143`.

**Existing problem:** Build settings and concurrency level change with system
load, operating mode, manual override, and a hysteresis buffer between modes.
Builds execute in stages.

**Proposed concurrency implementation:** Provide `submit(job)`,
`updateLoad(load)`, `overrideMode(mode)`, `runNext()`, and `close()`. Keep a
bounded queue, running count, admission limit, mode, and override generation.
Claim a job and increment the running count under the monitor; execute its
injected stage callback outside it; release capacity in `finally`. Lowering
the limit prevents new admissions rather than forcibly terminating existing
jobs. Admission resumes only at the specified hysteresis boundary. Explicit
queue order prevents newer jobs overtaking older eligible jobs.

**Bug opportunities:** Blocking stage work under the admission lock, leaked
running counts after exceptions, stale override visibility, duplicate job
claims, shutdown waiters never being awakened, and queue starvation.

**Discriminating check:** Hold one stage callback, lower the limit, and verify
that load updates and cancellation remain accessible. When the callback fails,
capacity must still be released.

### 3. ResolvingVendingMachine -> isolated bundle purchase with rollback

**Source:** [`data.json:2643`](prompts_from_other_benchmarks/data.json#L2643),
`ClassEval_170`.

**Existing problem:** A multi-item purchase tentatively debits funds and stock,
records successful pins, and backtracks if a later item cannot be purchased.

**Proposed concurrency implementation:** Implement
`purchaseBundle(transactionId, requirements)`, `restock`, `insertFunds`,
`cancel`, and `snapshot`. Keep the reservation/undo record owned by a single
transaction, not one globally reusable stack. A simple version performs all
validation and commit atomically. A harder version reserves stock, invokes a
dispensing callback outside the lock, then commits or rolls back only that
transaction's reservations. Use integer monetary units. State whether funds
are machine-wide or session-owned.

**Bug opportunities:** One purchase clearing another's rollback stack, double
refunds, overselling, unsafe publication of transaction records, callback
deadlocks, and rollback that overwrites intervening restocks.

**Discriminating check:** Pause purchase A after its first reservation, let B
purchase or restock, then fail A's second item. A's rollback must preserve B's
changes and release only A's reservations.

### 4. HarmonicBufferedStream -> exactly-once completion with reentrant callbacks

**Source:** [`data.json:4513`](prompts_from_other_benchmarks/data.json#L4513),
`ClassEval_290`.

**Existing problem:** Reads update an accumulated buffer and mathematical phase
state. EOF closes internal resources and invokes a callback over buffered data,
then breaks the callback reference cycle.

**Proposed concurrency implementation:** Supply a `ChunkSource` and
`CompletionListener` interface in the Java file. Implement `readChunk()`,
`close()`, and `snapshot()` with `OPEN`, `READING`, `COMPLETING`, and terminal
states. Serialize source consumption without holding the state monitor during
blocking source reads. EOF and explicit close compete for one completion claim;
the listener gets a detached immutable result and runs outside the monitor.
Define whether close cancels or waits for an in-flight read and how read or
listener failures are surfaced.

**Bug opportunities:** Duplicate callbacks, callback-under-lock deadlock,
constructor `this` escape, mutable buffer exposure, close/read races, and a
failure leaving completion permanently in progress.

**Discriminating check:** Race EOF with close while the listener reenters
`snapshot()`. Require one completion invocation, a coherent result, and no
deadlock. Fault injection must still reach a terminal state.

### 5. UserSpaceProvisioner -> single-flight initialization with safe commit

**Source:** [`data.json:4`](prompts_from_other_benchmarks/data.json#L4),
`ClassEval_0`.

**Existing problem:** User registration, file installation, active status, and
an installation manifest form one lifecycle. Installation failure must not
leave the user marked active.

**Proposed concurrency implementation:** Implement `register`,
`activate`, `verify`, `reinstall`, and `snapshotMetrics` with per-user states
`PENDING`, `INSTALLING`, `ACTIVE`, and `FAILED`. Concurrent activations for one
user share one installation attempt. Capture configuration and claim the
attempt under a lock, invoke an injected installer outside it, then atomically
publish status and a copied manifest. A generation identifies each reinstall,
preventing an older attempt's completion or failure from overwriting a newer
one. Notify all relevant waiters on success and failure.

**Bug opportunities:** Duplicate installation, active status preceding manifest
publication, failure stranding waiters, manifest/configuration aliasing, stale
attempt commits, and slow installation holding a global lock.

**Discriminating check:** Pause installation for one user. Another user's
registration must complete; simultaneous activation of the paused user must
not invoke the installer twice.

### 6. VersionedSlotManager -> optimistic conditional reservation

**Source:** [`data.json:402`](prompts_from_other_benchmarks/data.json#L402),
`ClassEval_26`.

**Existing problem:** Schedule snapshots include validators; a reservation
requires both an acceptable client validator and absence of temporal conflict.
Successful changes update the state hash.

**Proposed concurrency implementation:** Expose `getSnapshot()`,
`reserve(slot, expectedRevision)`, and `cancel(id, expectedRevision)`.
Validator comparison, overlap checking, mutation, and revision advancement
form one indivisible operation. Return detached slot records paired with the
revision captured under the same lock. Use a monotonic revision in the Java
adaptation to avoid content-hash ABA if a schedule changes and later returns
to identical contents. Preserve the source's rule that touching endpoints
conflict, unless explicitly benchmarking another interval policy.

**Bug opportunities:** Separate locks for precondition checking and insertion,
data/version mismatch, leaked mutable snapshots, ABA, and lost cancellation.

**Discriminating check:** Two callers use the same revision for different,
non-overlapping additions. Only one conditional write may succeed; the loser
must fetch a new revision before retrying.

### 7. TaggedCapacityBuffer -> filtered bounded delivery and atomic transfer

**Source:** [`data.json:2566`](prompts_from_other_benchmarks/data.json#L2566),
`ClassEval_165`.

**Existing problem:** Messages have sizes, tags, and read state. Retrieval
selects the oldest matching unread message. Capacity reclamation prioritizes
selected tags before FIFO eviction, and buffers can transmit to other buffers.

**Proposed concurrency implementation:** Preserve that nonblocking eviction
API, then add a separately specified `putBlocking`/`takeMatching` variant.
Store copied tags and values with atomic size accounting and read-state
changes. Define whether retrieval merely marks a message read or removes it;
those are different capacity policies. An optional move operation must not
lose a source message when its destination rejects it. For two-buffer moves,
use consistent ordering; never wait for destination capacity while holding
the source monitor.

**Bug opportunities:** Double delivery, incorrect byte accounting, tag aliasing,
opposite-direction transfer deadlock, filtered waiters receiving the wrong
notification, and unfair service among eligible consumers.

**Discriminating check:** Race two matching consumers for one message, then
race opposite-direction transfers. Exactly one consumer receives that
message, and transfers must not deadlock or duplicate it.

### 8. AdaptiveTrustVault -> metric-dependent expiration with atomic decisions

**Source:** [`data.json:2986`](prompts_from_other_benchmarks/data.json#L2986),
`ClassEval_192`.

**Existing problem:** Retrieval outcomes update a confusion matrix; the
resulting F1 score changes effective TTL and therefore eviction behavior.

**Proposed concurrency implementation:** Provide `deposit`,
`attemptRetrieve`, `cleanup`, and `snapshotMetrics`. Specify one exact order
for a retrieval: capture time, classify the attempt against a defined entry
state, update counters, compute the new TTL, then decide expiration/retrieval.
Handle zero denominators explicitly. Decide whether a changed TTL applies
retroactively to existing entries; the source's dynamic cleanup makes that a
particularly interesting variant. Return copied payloads and one coherent
metric snapshot.

**Bug opportunities:** Lost metric increments, recursive helper locking,
inconsistent counters and TTL, expiration based on mixed metric versions,
credential/payload escape, and repeated eviction scans monopolizing the lock.

**Discriminating check:** Concurrent retrieval attempts must each contribute
exactly once to the counters. A cleanup/retrieval race must follow the stated
decision order, not whichever helper reads the latest individual field.

### 9. SecureMetricAggregator -> expiring statistics over one consistent snapshot

**Source:** [`data.json:1675`](prompts_from_other_benchmarks/data.json#L1675),
`ClassEval_108`. Related variant:
[`data.json:3125`](prompts_from_other_benchmarks/data.json#L3125),
`ClassEval_201`, `TTLStatisticalAggregator`.

**Existing problem:** Insertions and statistical queries purge expired values.
The former provides mean/median/mode; the latter includes variance and standard
deviation from a common extracted dataset.

**Proposed concurrency implementation:** Implement `put`,
`removeExpired`, and `statistics()`. Capture one clock value and copy the
eligible measurements under a monitor; compute expensive statistics outside
it. Return count, mean, median, and variance for that same snapshot, with an
explicit empty-dataset contract. Inject a clock for reproducible expiration.
Treat this as a proposed snapshot-semantic alternative to holding the lock
through the source's entire computation.

**Bug opportunities:** Live-array escape, expiry inconsistencies between
statistics, mutation during sorting, incorrectly cached results, and long
computations delaying writers.

**Discriminating check:** Insert and expire values between computation stages.
All returned statistics must still describe one captured dataset, and input
arrays modified by callers must not change stored measurements.

### 10. TemporalForexCache -> coherent multi-key, time-sensitive reads

**Source:** [`data.json:291`](prompts_from_other_benchmarks/data.json#L291),
`ClassEval_19`.

**Existing problem:** Currency conversion requires two non-expired rates;
updates attach expiration metadata and maintenance removes stale rates.

**Proposed concurrency implementation:** Expose `updateRate`,
`updateRatesBatch`, `convert`, and `prune`. Capture both rates, their revision,
and a single observation time under one synchronization protocol. Copy a
batch's inputs before retaining them. Conversion can calculate outside the
lock once it owns immutable captured rates. The batch API is an addition that
makes mixed-generation results objectively testable.

**Bug opportunities:** Source and destination rates from different committed
batches, mismatched rate/expiry fields, prune racing with replacement, and
publication of partially populated rate records.

**Discriminating check:** Alternate between batches `(A=1, B=2)` and
`(A=2, B=4)` against the common base currency. Converting one unit from A to B
must always yield two, never a mixed-batch result.

### 11. BitmaskedTimelineStage -> atomic status with ordered external reporting

**Source:** [`data.json:3969`](prompts_from_other_benchmarks/data.json#L3969),
`ClassEval_255`.

**Existing problem:** A bitmask controls mutually exclusive outcome states,
an additive logged flag, a message, and cached display labels. Logging calls
an external timeline object.

**Proposed concurrency implementation:** Supply `success`, `warning`,
`failure`, `skip`, `log`, and `snapshot`. Update the mask, message, label, and
revision as one transition. Copy event records under the state lock and deliver
them outside it. If delivery order is required, use a separate ordered
dispatcher protocol: simply releasing the lock before each caller reports
does not preserve event order. Define whether outcomes are replaceable or
terminal; neither policy should be silently inferred.

**Bug opportunities:** Lost bitwise read-modify-write updates, inconsistent
label/mask pairs, external logger deadlock, event reordering, and premature
listener registration during construction.

**Discriminating check:** Race `failure` with `log` and require an exclusive
outcome plus the additive logged bit. A reentrant logger must observe a
coherent snapshot without deadlocking.

### 12. InventoryTransactionBuffer -> owner-aware staged commit

**Source:** [`data.json:1353`](prompts_from_other_benchmarks/data.json#L1353),
`ClassEval_87`.

**Existing problem:** One staged inventory delta and its tags can replace a
previous uncommitted delta. Commit requires matching required/forbidden tags;
projected quantities combine staged and committed state.

**Proposed concurrency implementation:** Retain the single staging slot but
add `stage(ownerId, delta, tags)` returning a generation token and
`commit(ownerId, generation, constraints)`. A replaced generation cannot
commit the new owner's contents. Check ownership, tags, stock bounds, apply
the delta, and clear staging atomically. Copy the delta and tag arrays on
input; return detached committed/projected snapshots.

**Bug opportunities:** A caller committing another caller's transaction,
tag/quantity mismatches, double commit, stale generation acceptance, and
mutable staged-data escape.

**Discriminating check:** A stages a delta; B replaces it; A commits using
its old token. A must fail without applying or clearing B's delta.

### 13. ChatSessionStream -> atomic structured history and stream history

**Source:** [`data.json:2248`](prompts_from_other_benchmarks/data.json#L2248),
`ClassEval_145`.

**Existing problem:** Sending messages updates user message records and a
linear text stream. Commands can enter through `write`; `read`/`seek` share
a cursor; close prevents writes while retaining history.

**Proposed concurrency implementation:** Give each accepted message a sequence
number and atomically update both representations. Define shared-cursor reads
as consuming disjoint ranges; offer separate reader handles only in a distinct
variant. Race-safe close chooses a definite final sequence and rejects later
writes. Preserve readable history after close and return copied message
records, not the internal user list.

**Bug opportunities:** Structured/text history divergence, cursor lost updates,
send racing with user removal, mutable message escape, and writes accepted
after closure.

**Discriminating check:** Race send against close and user removal. Each accepted
message appears exactly once in both histories; rejected messages appear in
neither. Concurrent shared-cursor reads must not consume the same range twice.

### 14. GomokuVersionState -> linearizable moves with coherent version metadata

**Source:** [`data.json:2264`](prompts_from_other_benchmarks/data.json#L2264),
`ClassEval_146`.

**Existing problem:** Board state, move count, current player, winner, and
serialized version string are coupled. A winning state changes the version
metadata.

**Proposed concurrency implementation:** Provide
`makeMove(player, row, column, expectedRevision)`, `snapshot`, and `reset`.
Validation, stone placement, winner detection, turn change, and revision
publication form one transition. A deep snapshot pairs board contents with
metadata. Do not use the source's move-count-based comparison alone as a
unique concurrency token: different positions can have equal move counts.
Include a reset generation.

**Bug opportunities:** Two moves accepted for one turn, two writes to the same
cell, metadata published before the board, nested-array escape, stale moves
after reset, and moves committed after victory.

**Discriminating check:** Two requests use one expected revision, followed by
a stale request after reset. Only one initial move succeeds, and the old
generation must not modify the reset board.

### 15. ThermostaticCircuitStream -> recoverable blocking circuit breaker

**Source:** [`data.json:492`](prompts_from_other_benchmarks/data.json#L492),
`ClassEval_32`.

**Existing problem:** Writes generate heat; overheating disables operations;
cooling restores operation; changing a threshold can trip the stream; close
is permanent. Disabled reads return empty data in the source.

**Proposed concurrency implementation:** Add a clearly distinct blocking
`writeWhenCool`/`readWhenReady` variant. Coordinate buffer contents, heat,
threshold, cursor, and mode using guarded `while` waits. Cooling, threshold
changes, and close must awaken affected waiters. Cooling must be callable
without waiting behind an operation that needs cooling. Define oversized
writes as rejected rather than requests that can never become eligible.

**Bug opportunities:** Busy waiting, missed wakeups, waiting while retaining
another needed lock, mode/temperature mismatch, and close resurrected by a
later cooling operation.

**Discriminating check:** Block a write on heat, then cool or close from another
thread. The writer must either proceed under the new valid state or terminate
with the specified closed result.

### 16. VerifiableMailbox -> capacity, deduplication, and validator coherence

**Source:** [`data.json:3286`](prompts_from_other_benchmarks/data.json#L3286),
`ClassEval_211`.

**Existing problem:** Conditional receipt rejects existing content validators.
FIFO eviction reclaims capacity and rebuilds strong/weak validator sets.
Fetch and synchronization headers consult those sets.

**Proposed concurrency implementation:** Implement `receive`,
`fetchIfMatch`, `prune`, and `snapshotHeader`. Duplicate checking, eviction,
insertion, size accounting, and validator updates are one atomic transition.
Use deterministic content identity with equality checks rather than assuming
a toy hash is collision-free. Reject an item larger than total capacity
before eviction. Decide whether fetch consumes a message or just copies it.

**Bug opportunities:** Duplicate messages from check-then-insert races,
validators retained after eviction, header/inbox mismatch, capacity overshoot,
and inbox record escape.

**Discriminating check:** Concurrent identical receipts admit one copy. After
evicting it, the validator disappears and a new receipt of that content can
be accepted under the stated deduplication policy.

### 17. PersistedScheduleContext -> version-ordered durable snapshots

**Source:** [`data.json:4045`](prompts_from_other_benchmarks/data.json#L4045),
`ClassEval_260`.

**Existing problem:** Schedule changes write to disk; conflicts produce log
entries. In-memory scheduling and persistence are tightly coupled.

**Proposed concurrency implementation:** Use an injected `ScheduleSink`
instead of real path resolution. Specify `reserve`, `cancel`, `flush`, and
`snapshot` with committed and durable revisions. A single ordered flusher can
write detached snapshots outside the state lock. An operation requiring
durability waits until its revision is persisted or a failure is surfaced.
Specify this deferred-durability variant separately from the original
immediate-sync semantics. The sink must not make an older revision replace
a newer persisted one.

**Bug opportunities:** Slow I/O under a global lock, out-of-order durable
snapshots, failure-shaped success, schedule/version mismatch, and mutable
snapshots passed to the sink.

**Discriminating check:** Delay persistence of revision 1 while revision 2
is committed. The durable state must not regress to revision 1 after reporting
revision 2 durable; a sink failure must unblock waiters with an error.

### 18. LexicalGraphProvider -> generation-consistent dependency queries

**Source:** [`data.json:1024`](prompts_from_other_benchmarks/data.json#L1024),
`ClassEval_66`.

**Existing problem:** Vocabulary and current sentence tokens determine
candidates and word dependencies. Normalization, token sequence, and token
index are related representations of one context.

**Proposed concurrency implementation:** Offer `replaceVocabulary`,
`setContext`, `findMatches`, and `dependencies`. Construct a complete immutable
context record before publishing it, pairing normalized tokens, indexes,
vocabulary revision, and generation. A resolver session pins one snapshot
across multiple queries rather than repeatedly reading the current context.
Perform expensive tokenization outside the monitor with an explicit ordering
rule for concurrent replacements.

**Bug opportunities:** Partially built index publication, mixed generations
across resolver calls, caller-owned word-array escape, and stale preprocessing
overwriting a newer replacement.

**Discriminating check:** Replace context repeatedly while a session asks for
matches and dependencies. All answers in that session must refer to its pinned
generation, never a hybrid graph.

### 19. Warehouse -> cancellable multi-item inventory reservations

**Source:**
[`ClassEval_data.json:8353`](prompts_from_other_benchmarks/ClassEval_data.json#L8353),
`ClassEval_95`.

**Existing problem:** Inventory, order creation, status changes, and tracking.
The original creates single-product orders with a default shipped status.

**Proposed concurrency implementation:** Introduce multi-product orders with
`reserveOrder`, `shipOrder`, `cancelOrder`, and `restock`. Track available and
reserved quantities separately. An order's state transition must consume or
release its reservation exactly once. A blocking variant can queue stock
requests, but must state whether strict FIFO or oldest-eligible admission is
intended: a large unsatisfiable head request otherwise blocks small requests.

**Bug opportunities:** Overselling, stock released twice, shipping after
cancellation, inconsistent order/inventory locking, and waiter starvation.

**Discriminating check:** Race shipping and cancellation for one reserved order.
Exactly one terminal transition succeeds, and stock accounting reflects that
outcome once.

### 20. RPGCharacter -> atomic two-character encounters

**Source:**
[`ClassEval_data.json:6161`](prompts_from_other_benchmarks/ClassEval_data.json#L6161),
`ClassEval_73`. Related lock-order baseline:
[`problemlist.csv:278-288`](prompts_from_other_benchmarks/problemlist.csv#L278).

**Existing problem:** An attack reads another character's defense and changes
health; healing and experience-driven level-up modify related attributes.

**Proposed concurrency implementation:** Make `attack(other)`, `heal`,
`gainExperience`, and `snapshot` atomic under a documented encounter policy.
For a two-character transition, acquire monitors using immutable unique IDs,
with a defined self-attack policy. Apply damage, death transition, and any
proposed victory reward together. Emit battle callbacks outside both monitors.
Keep health caps and level-up rules explicit rather than inferring new game
rules from concurrency needs.

**Bug opportunities:** A-attacks-B/B-attacks-A lock-order deadlock, duplicate
death rewards, damage computed from mixed attribute versions, health lost
updates, and mutable character-state escape.

**Discriminating check:** Simultaneous reciprocal attacks must terminate.
Concurrent attacks that kill one target must produce at most one death
transition and one associated reward.

### 21. MusicPlayer -> generation-safe playback and cancellation

**Source:**
[`ClassEval_data.json:5086`](prompts_from_other_benchmarks/ClassEval_data.json#L5086),
`ClassEval_61`.

**Existing problem:** Playlist editing, current song, playback/stop, previous/
next selection, shuffle, and volume control.

**Proposed concurrency implementation:** Add an injected playback backend and
completion callback. `play`, `stop`, `next`, and playlist edits update an
explicit playback generation; callbacks can only modify the generation that
started them. Invoke backend operations outside the state lock. Stable song
IDs prevent index invalidation when another thread removes or reorders songs.
Construct the player fully before registering callbacks.

**Bug opportunities:** Stale completion advancing a newer track, callback
deadlock, unsafely published listener state, playlist escape, and stop failing
to invalidate in-flight work.

**Discriminating check:** Start A, switch to B, then deliver A's delayed
completion callback. B must remain the current playback generation.

### 22. TelemetricCombinationGenerator -> cancellable parallel search with observers

**Source:** [`data.json:600`](prompts_from_other_benchmarks/data.json#L600),
`ClassEval_39`.

**Existing problem:** Combination search invokes constraints and reporter hooks
for selection, conflicts, backtracking, and completion.

**Proposed concurrency implementation:** Add `generate`, `addConstraint`, and
`cancel(runId)`. Pin immutable inputs/constraints per run, keep branch stacks
worker-owned, and publish results through bounded shared storage. Invoke
reporters outside locks; deliver one terminal event after workers stop.

**Bug opportunities:** Shared backtracking-stack corruption, reporter escape,
callback deadlock, cancellation visibility, and producers stranded at capacity.

**Discriminating check:** Cancel while a result producer is blocked; all workers
must exit and one terminal event must be delivered, including on reporter failure.

### 23. StreamedLedgerAccount -> on-demand funding with transactional pushback

**Source:** [`data.json:3951`](prompts_from_other_benchmarks/data.json#L3951),
`ClassEval_254`.

**Existing problem:** Reading ledger commands updates funds; withdrawal can
consume more input; undo reverses a transaction and pushes its line back.

**Proposed concurrency implementation:** Serialize command claims using source
generations. Read outside account locks, then commit each command once. An
undo token identifies its own committed delta; transfers must acquire funds
before entering an ordered two-account commit, never read while holding both.

**Bug opportunities:** Double command execution, foreign undo, cursor races,
blocking-under-lock, and reciprocal transfer deadlock.

**Discriminating check:** Race undo with another deposit and transfer. Undo
must reverse only its token's delta without replaying another caller's line.

### 24. ManagedPCIeNode -> authorized commands and resource lockdown

**Source:** [`data.json:1415`](prompts_from_other_benchmarks/data.json#L1415),
`ClassEval_91`.

**Existing problem:** Controller permissions gate device configuration commands;
device health can isolate the node; telemetry reports its state.

**Proposed concurrency implementation:** Add command IDs and a device generation.
Atomically validate controller, health, and generation when admitting a command.
Run an injected driver operation outside the monitor; publish completion only
for the captured generation. Specify whether revocation cancels admitted work
or merely prevents new admissions.

**Bug opportunities:** Permission/state TOCTOU, stale driver completion,
unsafe telemetry exposure, and callback-held-lock deadlock.

**Discriminating check:** Pause a driver operation, revoke its controller, and
enter lockdown. Completion must follow the stated admission/cancellation policy.

### 25. ConditionalAccessVault -> multi-party presence-gated retrieval

**Source:** [`data.json:122`](prompts_from_other_benchmarks/data.json#L122),
`ClassEval_8`.

**Existing problem:** Retrieving a payload requires specified personnel to be
present and restricted personnel to be absent.

**Proposed concurrency implementation:** Add `awaitRetrieve` and `close` beside
presence updates and deposit. Protect payload generation, copied requirements,
and presence together; wait in a predicate loop and awaken on every relevant
change. Define retrieval as a snapshot decision, not a guarantee that presence
remains unchanged after a copied payload is returned.

**Bug opportunities:** Mixed presence snapshots, mutable requirement escape,
missed notifications, and stale payload delivery.

**Discriminating check:** Change requirements while a waiter is blocked; it must
recheck the new payload's policy rather than return under obsolete requirements.

### 26. BioSamplePreservationUnit -> retrieval waiting on a changing target

**Source:** [`data.json:2673`](prompts_from_other_benchmarks/data.json#L2673),
`ClassEval_172`.

**Existing problem:** Sample tags determine a thermal target; retrieval can
trigger stabilization before returning the sample.

**Proposed concurrency implementation:** Replace active simulation loops with
`updateTemperature` and `awaitStableSample(expectedGeneration)`. Copy sample
and tags, publish their target atomically, and notify on temperature, replacement,
compromise, and close. A waiter either receives its requested stable generation
or explicitly fails when that generation is replaced.

**Bug opportunities:** Waiting while blocking temperature updates, target/sample
mismatch, compromised-state visibility, and payload escape.

**Discriminating check:** Replace a sample while its waiter awaits stability;
the waiter must not return the replacement under the first sample's target.

### 27. SemanticXMLRegistry -> optimistic document migration

**Source:** [`data.json:2689`](prompts_from_other_benchmarks/data.json#L2689),
`ClassEval_173`.

**Existing problem:** A transformation callback upgrades a document and its
semantic version; read/write operations maintain matching version metadata.

**Proposed concurrency implementation:** Represent XML as owned tree records.
Clone a document/version snapshot under lock, run the transformation outside
it, and commit only if the revision still matches. Return detached elements;
failed migrations leave both tree and version unchanged.

**Bug opportunities:** Mutable DOM escape, callback deadlock, version/data
mismatch, and stale migration overwriting a newer document.

**Discriminating check:** Two migrations start from one revision. Only one
commits, and a throwing transformation leaves no partial changes.

### 28. ConfigurableTicketRegistry -> hot-swapped backend with in-flight leases

**Source:** [`data.json:3936`](prompts_from_other_benchmarks/data.json#L3936),
`ClassEval_253`.

**Existing problem:** Configuration changes can reconnect the ticket database;
authorization and production mode gate operations.

**Proposed concurrency implementation:** Substitute an injected backend for
SQLite. `execute` captures policy and acquires a lease on the current backend.
`reconfigure` publishes a fully initialized replacement and retires the old one
only after its leases drain. Open/close/backend calls execute outside the state
monitor; define policy changes at operation admission.

**Bug opportunities:** Use-after-close, partial backend publication, lease leaks,
and slow connection setup starving normal operations.

**Discriminating check:** Hold an operation on backend A, switch to B, and verify
new operations use B while A remains open until its operation releases it.

### 29. SQLBackedMemoryStore -> atomic current value and revision history

**Source:** [`data.json:3335`](prompts_from_other_benchmarks/data.json#L3335),
`ClassEval_214`.

**Existing problem:** Modifying an entry archives its former value and generates
statements for current and history storage.

**Proposed concurrency implementation:** `modify(id, expectedRevision, value)`
atomically archives the exact predecessor and publishes the successor. Return
per-operation statement records rather than relying on a shared
`last_executed_sql` field. An optional ordered sink consumes immutable commits
outside the monitor and explicitly reports persistence failure.

**Bug opportunities:** Missing/duplicate history, aliased metadata, operation
result overwritten by another thread, and sink calls under locks.

**Discriminating check:** Two edits share an expected revision; only one succeeds
and exactly its predecessor appears once in history.

### 30. ResolutionLedger -> budget transfers coupled to solver events

**Source:** [`data.json:899`](prompts_from_other_benchmarks/data.json#L899),
`ClassEval_58`.

**Existing problem:** Solver rounds charge funds, pins reward progress,
backtracking charges penalties, and ledgers transfer budgets.

**Proposed concurrency implementation:** Apply event IDs exactly once and update
balance, insolvency, and audit records together. Use ordered ledger locks for
transfers and callbacks outside both. Define whether insufficient budget aborts
a round or enters a funded-wait state; deposits must awaken funded waiters.

**Bug opportunities:** Replayed charges/rewards, balance/status mismatch,
opposite transfer deadlock, and event-log escape.

**Discriminating check:** Concurrently replay one backtracking event while
transferring funds; charge once and conserve funds across both ledgers.

### 31. TimeBoundCapitalStore -> earliest-expiring withdrawals and allocation moves

**Source:** [`data.json:2441`](prompts_from_other_benchmarks/data.json#L2441),
`ClassEval_157`.

**Existing problem:** Funds occupy expiring labeled allocations; withdrawals
consume valid allocations and transfers preserve remaining validity.

**Proposed concurrency implementation:** Capture one time per operation, withdraw
earliest-expiring funds atomically, and update the cached total in the same
transition. Move allocations using ordered store locks, preserving the original
absolute deadline. Define destination-ID collision and insufficient-funds
behavior before mutation.

**Bug opportunities:** Double spend, deadline reset, expiry/total inconsistency,
allocation escape, and transfer deadlock.

**Discriminating check:** Race withdrawal, expiration, and transfer on one
allocation. Its remaining funds must exist at most once and never regain TTL.

### 32. EmailJournalFile -> bounded journal with pinned readers

**Source:** [`data.json:4340`](prompts_from_other_benchmarks/data.json#L4340),
`ClassEval_279`.

**Existing problem:** Writes buffer text, flush creates messages, FIFO eviction
reclaims capacity, and reads maintain a cursor across messages.

**Proposed concurrency implementation:** Assign immutable journal-entry IDs;
detach the draft atomically on flush and publish it once. Decide whether readers
pin entries against eviction or consume detached snapshots; do not silently
invalidate active cursors. Close claims the final draft and prevents new writes.

**Bug opportunities:** Double flush, lost draft, cursor use-after-eviction,
capacity races, and blocked writers preventing reader release.

**Discriminating check:** Evict an entry during a partial read and race flush
with close. Reads obey the chosen pin/snapshot policy and drafts publish once.

### 33. TelemetricGomokuEngine -> bounded asynchronous audit of accepted moves

**Source:** [`data.json:3142`](prompts_from_other_benchmarks/data.json#L3142),
`ClassEval_202`.

**Existing problem:** Moves update a board and masked audit records; logging can
use an asynchronous queue and explicit flushes.

**Proposed concurrency implementation:** Couple each committed move to one
immutable, sequence-numbered audit record. Reserve bounded audit capacity before
committing, or explicitly reject the move; do not drop records silently. Flush
detached batches outside the board monitor, with ordered acknowledgment and
an explicit sink-failure state.

**Bug opportunities:** Accepted-but-unlogged moves, queue races, duplicate flush,
and logging backpressure blocking its own drain.

**Discriminating check:** Fill the audit queue and block the sink. Move rejection,
backpressure, and recovery must preserve the move/audit invariant.

### 34. AuditedMatchGrid -> optimistic path validation with audit commit

**Source:** [`data.json:4448`](prompts_from_other_benchmarks/data.json#L4448),
`ClassEval_286`.

**Existing problem:** Connecting/removing matching tiles involves pathfinding,
performance measurement, masking, and queued logs.

**Proposed concurrency implementation:** Capture a grid revision and compute
routes on a deep snapshot outside the lock. Commit removal of both tiles and
one audit event only if that revision and both tiles still match. Invoke the
log sink outside the grid monitor; bounded retries explicitly fail on contention.

**Bug opportunities:** Stale-path commits, half-removals, mutable-grid escape,
duplicate audit events, and unbounded retry starvation.

**Discriminating check:** Change a route obstruction while pathfinding is paused;
the stale result must not authorize removal.

### 35. VectorSimilarityGate -> versioned calibration and one-result finalization

**Source:** [`data.json:2912`](prompts_from_other_benchmarks/data.json#L2912),
`ClassEval_187`.

**Existing problem:** Similarity against reference vectors and IDF weights
drives a timeline stage's status and reporting.

**Proposed concurrency implementation:** Deep-copy calibration inputs, publish
one reference/weights/threshold generation, and compute scores outside locks.
`validate(expectedGeneration)` commits status only for that calibration; define
whether one result wins or newer attempts supersede older ones. Invoke timeline
callbacks outside the monitor.

**Bug opportunities:** Mixed calibration, array escape, stale success after
recalibration, and logging callback deadlock.

**Discriminating check:** Recalibrate during a long validation. Its old result
cannot finalize the new generation.

### 36. BufferedHRLogProxy -> single-flight lazy ingestion before mutation

**Source:** [`data.json:3612`](prompts_from_other_benchmarks/data.json#L3612),
`ClassEval_232`.

**Existing problem:** Employee queries and additions can force stream ingestion
before accessing employee records.

**Proposed concurrency implementation:** Add `UNREAD`, `LOADING`, `READY`, and
`FAILED` states. One caller owns source consumption; others wait without holding
locks needed by it. Parse into private records and publish atomically before
manual edits proceed. Propagate failure to every waiter and detach returned
employee records.

**Bug opportunities:** Duplicate ingestion, load overwriting manual edits,
partial record publication, and failure-stranded waiters.

**Discriminating check:** Race first query with first addition while loading is
paused; one load occurs and the manual addition survives publication.

### 37. BufferedSupplyChainStream -> all-or-nothing manifest ingestion

**Source:** [`data.json:432`](prompts_from_other_benchmarks/data.json#L432),
`ClassEval_28`.

**Existing problem:** Reading tees a manifest; EOF invokes parsing that populates
inventory and orders alongside manual operations.

**Proposed concurrency implementation:** Claim EOF processing once, parse
commands outside locks, then validate and apply a complete manifest against
one inventory revision. A failed/conflicting manifest leaves no partial orders
or stock changes; define retry rather than replaying already committed commands.
Expose detached inventory/audit snapshots.

**Bug opportunities:** Duplicate EOF commit, partial stock/order publication,
manual-update loss, and parser calls under locks.

**Discriminating check:** Pause parsing, consume stock manually, then resume.
The manifest must revalidate and reject or safely retry, never oversell.

### 38. BufferedRecruitmentStream -> snapshot matching across ingestion and withdrawal

**Source:** [`data.json:3080`](prompts_from_other_benchmarks/data.json#L3080),
`ClassEval_198`.

**Existing problem:** EOF ingests candidate records and triggers matching while
job postings and candidate records remain editable.

**Proposed concurrency implementation:** Single-flight batch ingestion publishes
one candidate revision. Match against captured job/candidate snapshots outside
the monitor; revalidate before creating proposed interview reservations.
Use stable candidate IDs and tombstone generations so delayed ingestion does
not resurrect an explicitly withdrawn candidate.

**Bug opportunities:** Duplicate imports, stale matches, record escape,
withdrawal resurrection, and callback blocking under locks.

**Discriminating check:** Withdraw a candidate while its import or matching is
paused; a stale result cannot book an interview for that withdrawn generation.

### 39. ActiveConfigMap -> dynamically retimed, cancellable waits

**Source:** [`data.json:4121`](prompts_from_other_benchmarks/data.json#L4121),
`ClassEval_265`.

**Existing problem:** Normalized configuration keys control weighted virtual
delays, jitter, and cancellation.

**Proposed concurrency implementation:** Replace sleep loops with monitor-based
timed waits. `updateTiming`, `advanceClock`, and `cancel` publish coherent timing
state and notify waiters. Specify whether existing waits adopt changed factors;
compute remaining time from a monotonic clock, guard predicates in a loop, and
never busy-spin when a factor reaches zero.

**Bug opportunities:** Cancellation visibility, missed retiming notifications,
configuration escape, and zero-factor spin.

**Discriminating check:** Reduce a delay or cancel during a wait; it must react
without waiting out the obsolete original duration.

### 40. TemporalPromotionEngine -> expiring strategy execution with checkout snapshots

**Source:** [`data.json:36`](prompts_from_other_benchmarks/data.json#L36),
`ClassEval_2`.

**Existing problem:** Promotion codes reference discount strategies with TTL;
checkout selects a valid strategy and cleanup removes expired codes.

**Proposed concurrency implementation:** Capture a copied cart, promotion
generation, strategy, and observation time atomically. Invoke strategy code
outside the monitor. Define snapshot eligibility at admission, or revalidate
before a separate final checkout commit; these are different contracts.
Replacing a promotion cannot let old cleanup delete its new generation.

**Bug opportunities:** Strategy/map escape, callback deadlock, mixed cart/rules,
and replacement-versus-expiry races.

**Discriminating check:** Replace a promotion during delayed evaluation and
cleanup; the returned price follows its stated captured or revalidated policy.

### 41. RankedMetricSessionStore -> ranking updates that renew session expiry

**Source:** [`data.json:1505`](prompts_from_other_benchmarks/data.json#L1505),
`ClassEval_97`.

**Existing problem:** Ranking history determines MRR/MAP and extends session
TTL through performance bonuses.

**Proposed concurrency implementation:** Apply ranking task IDs once, copy
result arrays, and update history, metrics, and expiry atomically. Cleanup
must recheck entry generation after scanning; a renewed session must not be
removed using an earlier deadline. Decide whether expired sessions can be
renewed or require an explicit fresh session.

**Bug opportunities:** Lost task updates, duplicate bonuses, result-array escape,
and renewed-session eviction.

**Discriminating check:** Pause cleanup after observing an old deadline, then
renew the session; stale cleanup must not delete the renewed generation.

### 42. TimeBoundAssetLedger -> liquidation versus expiry and price changes

**Source:** [`data.json:185`](prompts_from_other_benchmarks/data.json#L185),
`ClassEval_12`.

**Existing problem:** Acquisition debits cash; assets expire; liquidation credits
only valid positions; reconciliation removes expired assets.

**Proposed concurrency implementation:** Give positions immutable IDs and
generations; validate time, claim liquidation, remove the position, and credit
cash in one transition. An added market-price update API must state whether
liquidation uses its admission quote or a revalidated quote. Copy detail records
and never reset deadlines on inspection.

**Bug opportunities:** Double liquidation, expired credit, balance/ledger races,
and mutable position escape.

**Discriminating check:** Race two liquidations with expiry; at most one credit
occurs, using a quote and validity state allowed by the contract.

### 43. ComponentAssemblyOrder -> scarce-component reservation and build quotas

**Source:** [`data.json:1848`](prompts_from_other_benchmarks/data.json#L1848),
`ClassEval_119`.

**Existing problem:** Component availability, cost multipliers, build manifests,
and a resource quota determine assembly.

**Proposed concurrency implementation:** Reserve component quantities and quota
for an assembly generation under one monitor. Pin compiler/platform settings;
execute compilation outside it. Commit the artifact manifest or release only
that generation's reservations on failure. Different orders can build concurrently
when reservations permit.

**Bug opportunities:** Overbooking, quota leaks, foreign rollback, mutable
manifest escape, and compilation blocking shared admission.

**Discriminating check:** Two assemblies need the last component; only one owns
it, and a failing assembly returns exactly its reserved quota and quantities.

### 44. ScriptDeploymentCart -> budget-checked frozen deployment plan

**Source:** [`data.json:462`](prompts_from_other_benchmarks/data.json#L462),
`ClassEval_30`.

**Existing problem:** Staged scripts and replicas incur resource costs; checkout
validates the budget and installs the plan.

**Proposed concurrency implementation:** `checkout(expectedCartRevision)`
freezes scripts, replicas, paths, and budget into an owned plan before invoking
an installer outside locks. Edits either affect the next plan or are rejected
while checkout runs. Track per-replica outcomes and explicitly surface partial
installation instead of publishing unconditional success.

**Bug opportunities:** Budget TOCTOU, duplicate checkout, plan aliasing, and
an old completion overwriting new cart state.

**Discriminating check:** Add replicas during a paused checkout; they cannot
enter the frozen plan without its budget being revalidated.

### 45. ManifestDrivenBuilder -> configuration reload versus build acknowledgment

**Source:** [`data.json:2098`](prompts_from_other_benchmarks/data.json#L2098),
`ClassEval_135`.

**Existing problem:** A manifest supplies build configuration and artifacts;
the pipeline later writes status to that same configuration file.

**Proposed concurrency implementation:** Parse configuration privately and
publish a complete generation. Builds pin one manifest; status writes include
an expected source generation and use an injected compare-and-replace sink.
Reading, building, and writing occur outside the state monitor. An old build
cannot replace newly loaded configuration with its status.

**Bug opportunities:** Partial configuration publication, stale file overwrite,
artifact-list escape, and I/O under locks.

**Discriminating check:** Reload manifest B during build A; A's status update
must not overwrite B's configuration.

### 46. DistributionInventoryManager -> derived build context invalidation

**Source:** [`data.json:1959`](prompts_from_other_benchmarks/data.json#L1959),
`ClassEval_126`.

**Existing problem:** Staged component weights determine platform-specific
compilation and finalized output paths.

**Proposed concurrency implementation:** Version inventory changes and cache
derived build context only for that revision. `startBuild` captures components,
compiler, and paths together; staging/withdrawal invalidates future context
without mutating an active plan. Build outside locks and return an owned artifact
result tied to its inventory revision.

**Bug opportunities:** Stale compiler decisions, mixed paths/components,
inventory escape, and obsolete result publication.

**Discriminating check:** Add a binary component while context finalization is
paused; the next plan must not reuse the old pure-module-only context.

### 47. AuthenticatedBuildOrchestrator -> agent readiness barrier and revocation

**Source:** [`data.json:2595`](prompts_from_other_benchmarks/data.json#L2595),
`ClassEval_167`.

**Existing problem:** Registered agents authenticate, finalize environments,
execute builds, and collectively determine distribution readiness.

**Proposed concurrency implementation:** Add `awaitReady`, `revokeAgent`, and
`close`. Atomically publish complete per-agent environments and capture the
roster generation for readiness waits. Define whether new registration invalidates
the barrier. Builds acquire an admitted-agent generation, then run outside locks;
stale completions cannot reactivate revoked agents.

**Bug opportunities:** Partly initialized environment escape, roster races,
missed readiness notifications, and revocation overwritten by completion.

**Discriminating check:** Register an additional agent during a readiness wait;
the wait follows the stated fixed-roster or current-roster rule.

### 48. QueryStrategyRegistry -> replaceable strategy dispatch

**Source:** [`data.json:201`](prompts_from_other_benchmarks/data.json#L201),
`ClassEval_13`.

**Existing problem:** Named functions are registered/replaced and invoked to
generate queries; an accessor exposes the strategy map.

**Proposed concurrency implementation:** Provide `register`, `replace`,
`remove`, and `generate`. Capture one strategy reference and copied request
under lock, then invoke outside it. Define replacement as affecting future
admissions, not calls already admitted. Return detached registry descriptions
rather than a live mutable map.

**Bug opportunities:** Check-then-register races, registry escape, callback
deadlock, and inconsistent replace/remove visibility.

**Discriminating check:** A strategy replaces itself during execution; the call
finishes without deadlocking and later requests use its replacement.

### 49. CamelCaseRegistryProvider -> normalized dependency registration and ownership

**Source:** [`data.json:1911`](prompts_from_other_benchmarks/data.json#L1911),
`ClassEval_123`.

**Existing problem:** Candidate identifiers and dependencies normalize to
camelCase; lookups and preferences consult one registry.

**Proposed concurrency implementation:** Normalize before atomic
`registerIfAbsent`; canonical aliases must designate one slot. Store owned
candidate/dependency records and pin a registry revision per resolution session.
If inspecting a candidate invokes user code, do it before acquiring the registry
monitor and revalidate before publication.

**Bug opportunities:** Duplicate aliases, partially published dependencies,
mutable candidate escape, and callback lock inversion.

**Discriminating check:** Register `build_tool` and `buildTool` concurrently;
one canonical registration wins and session dependencies stay coherent.

### 50. CamelCaseInstallRegistry -> normalized batch settings and dry-run isolation

**Source:** [`data.json:958`](prompts_from_other_benchmarks/data.json#L958),
`ClassEval_62`.

**Existing problem:** Normalized registry settings control script installation,
distribution-derived defaults, and dry-run behavior.

**Proposed concurrency implementation:** Add atomic `updateOptions` and
`runPlan`. Resolve external defaults outside locks, then publish only if the
settings revision still matches. Capture source, destination, artifact list,
and dry-run flag together; a dry-run plan must never acquire execution side
effects after later configuration changes.

**Bug opportunities:** Mixed path settings, dry-run visibility races, mutable
option-array escape, and external distribution callbacks under locks.

**Discriminating check:** Toggle dry-run during a paused plan; it retains its
captured policy and cannot accidentally install.

### 51. LexicalAttributeRegistry -> atomic longest-match metadata resolution

**Source:** [`data.json:1896`](prompts_from_other_benchmarks/data.json#L1896),
`ClassEval_122`.

**Existing problem:** Sentence analysis selects the longest registered term
and resolves its associated metadata.

**Proposed concurrency implementation:** Add replace/remove operations and
return `(term, metadata, revision)` from one captured vocabulary snapshot.
Sanitize sentences outside locks. A match and its metadata must come from
the same revision, with deterministic tie-breaking and owned metadata records.
Expose copied keys, not a backing collection view.

**Bug opportunities:** Match/metadata TOCTOU, deleted-term lookup races,
metadata escape, and analysis monopolizing the registry lock.

**Discriminating check:** Replace a winning term's metadata during analysis;
the result must pair that term with metadata from its captured revision.

### 52. SemanticCorpusWrapper -> incremental index and snapshot analysis

**Source:** [`data.json:616`](prompts_from_other_benchmarks/data.json#L616),
`ClassEval_40`.

**Existing problem:** Editable documents support per-document and whole-corpus
word-frequency analysis.

**Proposed concurrency implementation:** Maintain document revisions and an
added aggregate token index. Tokenize replacements privately, then atomically
remove old counts and add new counts if the expected document revision matches.
Produce top-N results from one index snapshot outside locks; copies must not
expose index buckets.

**Bug opportunities:** Double-counted replacement, index/document divergence,
stale analysis cache, and mutable result escape.

**Discriminating check:** Race two replacements of one document; aggregate
counts must reflect exactly the committed document, not both updates.

### 53. OperationalHazardGrid -> atomic reconfiguration of coupled grids

**Source:** [`data.json:2235`](prompts_from_other_benchmarks/data.json#L2235),
`ClassEval_144`.

**Existing problem:** Configuration sets grid dimensions, hazard counts,
retry limits, and sweep behavior; reload regenerates the simulation.

**Proposed concurrency implementation:** Build hidden grid, visible grid, and
rules privately, then publish one configuration generation. `verifySector`
validates bounds and mutates score/view under that generation. Reload must not
mix old dimensions with new arrays; decide whether it cancels or rejects
in-flight sweeps.

**Bug opportunities:** Unsafe multi-array publication, reset/update loss,
configuration escape, and score races.

**Discriminating check:** Shrink the grid while a sweep at an old valid position
is paused; it must fail generation validation, not access the new grid out of bounds.

### 54. GameStateConfigurationBroker -> atomic spatial moves and configuration activation

**Source:** [`data.json:4481`](prompts_from_other_benchmarks/data.json#L4481),
`ClassEval_288`.

**Existing problem:** Moving/pushing packets onto endpoints activates
configuration; the grid, cached configuration, and move budget are coupled.

**Proposed concurrency implementation:** `move(expectedRevision, direction)`
atomically updates agent position, pushed packets, budget, production state,
and derived cache. Return detached configuration snapshots. Spawn and reset
use the same revision protocol; compute collision plans privately and revalidate
before applying them.

**Bug opportunities:** Overlapping pushes, grid/cache divergence, negative
budget from lost checks, and mutable packet escape.

**Discriminating check:** Two moves use one revision; only one changes the grid,
and activated settings exactly match the resulting packet positions.

### 55. TemporalContextBuffer -> scheduled payloads with capability revocation

**Source:** [`data.json:1614`](prompts_from_other_benchmarks/data.json#L1614),
`ClassEval_104`.

**Existing problem:** Tagged payloads occupy non-overlapping slots; exclusion
purges remove scheduled payloads.

**Proposed concurrency implementation:** Add capability updates and reservation
IDs. Validate capability membership and temporal conflicts in the insertion
transition, copying tags and payloads. Specify whether capability removal
invalidates existing reservations or only future admissions. Purge and reads
must observe one timeline revision.

**Bug opportunities:** Capability/slot TOCTOU, insertion missed by purge,
mutable tags, and stale active-value snapshots.

**Discriminating check:** Race scheduling with exclusion purge or capability
removal; the winner defines one consistent valid reservation state.

### 56. TaggedResourceScheduler -> resource-state leases during reservations

**Source:** [`data.json:2021`](prompts_from_other_benchmarks/data.json#L2021),
`ClassEval_130`.

**Existing problem:** A mutable resource value and capabilities gate
non-overlapping reservations.

**Proposed concurrency implementation:** Return a reservation handle pinning
the resource-value generation. `updateResource` either rejects while active
leases exist or publishes a successor for future reservations; specify one
policy. Cancellation invalidates only its own handle. Availability, capability
checks, and lease creation use one synchronized transition.

**Bug opportunities:** Leaked mutable resource values, reservation/state mismatch,
double lease release, and updater starvation.

**Discriminating check:** Update a resource while a reservation is active;
the handle must not silently switch to an unrelated value.

### 57. MarketSessionManager -> risk-policy changes and schedule admission

**Source:** [`data.json:2381`](prompts_from_other_benchmarks/data.json#L2381),
`ClassEval_153`.

**Existing problem:** Strategy schedules require both temporal availability
and compliance with global risk filters.

**Proposed concurrency implementation:** Add atomic policy replacement and
`schedule(expectedPolicyRevision, parameters)`. Copy exclusions and strategy
settings. Define whether tightening a policy cancels, marks, or preserves
already admitted sessions; update schedules and active-constraint snapshots
according to that policy.

**Bug opportunities:** Mixed risk filters, policy/admission TOCTOU, aliased
exclusions, and cancellation racing with schedule publication.

**Discriminating check:** Tighten a limit during admission; a session cannot
claim the new revision while using the previous permissive filters.

### 58. AgentSessionManager -> identity-dependent lease duration

**Source:** [`data.json:2581`](prompts_from_other_benchmarks/data.json#L2581),
`ClassEval_166`.

**Existing problem:** Parsed client identity determines allowable access-window
duration and conflict checks.

**Proposed concurrency implementation:** Add identity/policy replacement and
return immutable leases. Capture parsed identity, trust policy, start, and
computed end together; publish after conflict validation. A renewal references
the existing lease ID and generation, not a mutable global browser field.

**Bug opportunities:** Header/parsed-state mismatch, overlong leases from mixed
policy versions, unsafe lease publication, and renewal lost updates.

**Discriminating check:** Downgrade identity during renewal; the resulting lease
must obey one explicitly admitted identity/policy generation.

### 59. MacroSimulationScheduler -> scheduled windows pinning scenario generations

**Source:** [`data.json:1990`](prompts_from_other_benchmarks/data.json#L1990),
`ClassEval_128`.

**Existing problem:** Named scenarios occupy time windows and supply market
data for a queried time; interactions enter an audit trail.

**Proposed concurrency implementation:** Add scenario replacement/removal and
pin each accepted window to an immutable scenario generation. Schedule,
cancel, and active-window lookup use coherent snapshots; copy scenario arrays
on input. Define whether replacement affects existing windows rather than
silently retargeting them.

**Bug opportunities:** Schedule/scenario TOCTOU, input-array escape, incorrect
audit ordering, and removed scenario dereference.

**Discriminating check:** Replace a scenario after scheduling a window; its
data follows the documented pinned or live-update policy consistently.

### 60. ContextualPlaybackBuffer -> fair selection under changing filters

**Source:** [`data.json:507`](prompts_from_other_benchmarks/data.json#L507),
`ClassEval_33`.

**Existing problem:** Required/excluded tags determine playable media; skip
and shuffle change selection.

**Proposed concurrency implementation:** Add `awaitPlayable`, close, and
completion generations. Atomically choose a matching stable media ID and
capture filter revision, then start playback outside locks. Notify on
enqueue/filter/close; an explicit oldest-eligible policy prevents shuffle
or new items from repeatedly displacing eligible older requests.

**Bug opportunities:** Filter/item mismatch, mutable tag escape, stale index
after shuffle, and missed wakeups.

**Discriminating check:** Change the filter while a consumer waits with a
nonempty but ineligible queue; it must wake when an item becomes eligible.

### 61. MarketMoodSonifier -> ordered stream processing with reset generations

**Source:** [`data.json:633`](prompts_from_other_benchmarks/data.json#L633),
`ClassEval_41`.

**Existing problem:** Successive market values determine trends, playlists,
volume, and session logs; reset clears processing state.

**Proposed concurrency implementation:** Add `submitTick`, `processNext`,
and `reset`. Sequence ticks and apply trend transitions in input order even
if preprocessing is parallel. Capture playlist mappings with each transition,
then notify an audio backend outside the monitor. Reset invalidates old work
and defines whether queued ticks are discarded.

**Bug opportunities:** Out-of-order trend computation, stale audio completion,
log/state divergence, and mutable playlist escape.

**Discriminating check:** Process two ticks out of completion order, then reset;
neither reordering nor old work may corrupt the new session's previous-value state.

### 62. IdentityJukebox -> coherent identity switching with compatibility gates

**Source:** [`data.json:2502`](prompts_from_other_benchmarks/data.json#L2502),
`ClassEval_161`.

**Existing problem:** Selected header strings are parsed into identity fields;
compatibility volume gates activation.

**Proposed concurrency implementation:** Parse selected headers privately, then
publish raw header, parsed identity, active flag, and selection generation
atomically. Next/shuffle use stable track IDs; stop invalidates pending activation.
Capture compatibility policy before validation and recheck it at commit.

**Bug opportunities:** Raw/parsed identity mismatch, stale activation after
stop, queue escape, and policy TOCTOU.

**Discriminating check:** Stop or raise the compatibility threshold during
parsing; a stale parse cannot activate an invalid selected identity.

### 63. ThermostaticFileTee -> heat-budget reservations for in-flight reads

**Source:** [`data.json:729`](prompts_from_other_benchmarks/data.json#L729),
`ClassEval_47`.

**Existing problem:** Reads raise temperature; a critical mode gates I/O;
recovery cools the wrapper and close invokes a buffered-data callback.

**Proposed concurrency implementation:** Reserve a bounded heat allowance
before invoking a source read outside locks, reconcile actual bytes afterward,
and release allowances on failure. Cooling and cancellation remain callable
while reads run. Close uses one callback claim after resolving outstanding
reads, with detached buffered data.

**Bug opportunities:** Concurrent reads overbooking heat, leaked allowances,
close/read races, and source/callback blocking under locks.

**Discriminating check:** Two reads compete for the last heat allowance;
only eligible work proceeds, and a throwing read returns its reservation.

### 64. AdaptiveMarketThermostat -> atomic feedback-derived constraint bundles

**Source:** [`data.json:853`](prompts_from_other_benchmarks/data.json#L853),
`ClassEval_55`.

**Existing problem:** Risk temperature and target derive several screening
filters; a high-risk safety lock overrides normal settings.

**Proposed concurrency implementation:** Treat each control step as one
revisioned transition publishing temperature, mode, and all filters together.
Calibration and manual overrides invalidate pending derived updates. Run
expensive convergence calculations on captured state outside the monitor;
never let a stale calculation undo a safety lock.

**Bug opportunities:** Mixed filter bundles, override lost updates, unsafe
array publication, and long computation under locks.

**Discriminating check:** Engage the safety lock while convergence is paused;
the stale result cannot restore permissive constraints.

### 65. MacroAdaptiveOrderSystem -> price-at-add reservations with atomic checkout

**Source:** [`data.json:369`](prompts_from_other_benchmarks/data.json#L369),
`ClassEval_24`.

**Existing problem:** Market indicators set spot prices when adding basket
items; checkout uses those captured prices and records an audit.

**Proposed concurrency implementation:** Add per-order IDs and inventory
reservations. Atomically capture market revision, reserve quantity, and append
a priced line; checkout closes that order once and consumes its reservations.
Cancellation restores its quantities, not later inventory snapshots.

**Bug opportunities:** Overselling, repricing already accepted lines,
checkout/add races, and basket/audit escape.

**Discriminating check:** Change indicators after adding an item and race
checkout with another add; only admitted lines enter the frozen priced order.

### 66. ContextualTransactionBuffer -> rule-tag changes and conditional freeze

**Source:** [`data.json:138`](prompts_from_other_benchmarks/data.json#L138),
`ClassEval_9`.

**Existing problem:** Context tags gate item additions, modify pricing,
and determine compliance.

**Proposed concurrency implementation:** Add
`freezeIfCompliant(expectedRevision, required, forbidden)`. Items, rule tags,
and total must come from one revision; a successful freeze prohibits subsequent
mutation. Copy item records and tags, and explicitly define whether changing
pricing tags reprices existing lines or only future additions.

**Bug opportunities:** Compliance TOCTOU, lost tag updates, mutable payload
views, and total calculated from mixed rules.

**Discriminating check:** Add a forbidden tag while freeze is attempted; the
frozen revision cannot claim compliance with incompatible tags.

### 67. StreamableOrderSession -> exactly-once checkout and stable receipt reads

**Source:** [`data.json:4633`](prompts_from_other_benchmarks/data.json#L4633),
`ClassEval_298`.

**Existing problem:** Stream writes add dishes; reads generate receipts;
checkout/close finalizes the order and buffer.

**Proposed concurrency implementation:** Atomically freeze selected dishes,
discounts, total, and final receipt on checkout. Concurrent checkout and close
share one finalization result. Before finalization, each reader pins one receipt
revision or explicitly resets its cursor when regenerated; do not splice
different receipts across a read.

**Bug opportunities:** Double checkout, stale discount totals, cursor races,
mutable menu escape, and post-close additions.

**Discriminating check:** Race add, checkout, and partial read; the final receipt
contains exactly the accepted lines at one discount revision.

### 68. VersionedAssessmentLedger -> strong writes and weak-equivalence reads

**Source:** [`data.json:3269`](prompts_from_other_benchmarks/data.json#L3269),
`ClassEval_210`.

**Existing problem:** Scores produce strong content validators and weak GPA
validators; conditional writes and wildcard mode consult them.

**Proposed concurrency implementation:** Require a strong revision for writes;
use weak equivalence only for explicitly permitted read responses. Update
scores, GPA, and validators atomically. Wildcard mode bypasses the precondition,
not synchronization. Copy nested student/course records.

**Bug opportunities:** Weak token authorizing stale writes, validator/data
mismatch, wildcard visibility races, and record escape.

**Discriminating check:** Change two course scores while keeping GPA unchanged;
the old weak-equivalent state must not authorize a conditional strong write.

### 69. FinalizedGomoku -> mutable-to-frozen transition with stable identity

**Source:** [`data.json:4185`](prompts_from_other_benchmarks/data.json#L4185),
`ClassEval_269`.

**Existing problem:** Winning locks further moves; hashing/equality inspect a
board that was mutable before finalization.

**Proposed concurrency implementation:** `freeze` publishes a deeply immutable
board result and cached hash once. Do not use a mutable board's hash as stable
identity; require freeze before content-key use. A winning move and freeze
must exclude concurrent later moves; equality uses immutable snapshots rather
than arbitrary nested object locking.

**Bug opportunities:** Hash/state instability, premature frozen publication,
nested-array escape, and reciprocal equality deadlock.

**Discriminating check:** Race the winning move with another move and freeze;
all frozen results have identical board contents and stable hashes.

### 70. LexicalNumberGraphProvider -> single-flight dependency-node construction

**Source:** [`data.json:4556`](prompts_from_other_benchmarks/data.json#L4556),
`ClassEval_293`.

**Existing problem:** Number names identify nodes whose dependencies are
linguistic numeric components; a resolution cache stores derived state.

**Proposed concurrency implementation:** Cache owned immutable nodes with
`BUILDING`, `READY`, and `FAILED` states. Coalesce requests for a node without
holding a global monitor through recursive dependency construction. Define a
well-founded component relation and detect self-dependency/cycles rather than
waiting on the builder's own incomplete entry.

**Bug opportunities:** Partial graph publication, duplicate node builds,
recursive-wait deadlock, and failure-stranded waiters.

**Discriminating check:** Concurrently request numbers sharing components;
components publish once and recursive construction cannot await itself.

### 71. CamelAttrDict -> canonical-key atomic operations and snapshot enumeration

**Source:** [`data.json:2817`](prompts_from_other_benchmarks/data.json#L2817),
`ClassEval_181`.

**Existing problem:** Mapping and attribute-style operations share a
camelCase-normalized backing dictionary.

**Proposed concurrency implementation:** Replace Python attribute hooks with
explicit Java APIs and add `putIfAbsent`, `replaceIfEqual`, and `snapshotKeys`.
Every API normalizes to the same key before lookup/mutation; owned values or
an explicit immutable-value contract prevent aliases from bypassing locks.

**Bug opportunities:** Duplicate canonical keys, inconsistent locking across
access APIs, backing-view escape, and lost conditional replacements.

**Discriminating check:** Race insertion through snake_case and camelCase
aliases; one canonical value wins and enumeration returns a stable snapshot.

### 72. ResolutionPerformanceReporter -> exactly-once event accounting and round snapshots

**Source:** [`data.json:586`](prompts_from_other_benchmarks/data.json#L586),
`ClassEval_38`.

**Existing problem:** Pin/backtrack/conflict events update solver metrics
and round history.

**Proposed concurrency implementation:** Add run, round, and event IDs.
Atomically apply a pin or its matching retraction once; freeze completed round
snapshots and reject late events under a stated policy. Separate concurrent
runs' counters; compute derived metrics from copied counters outside locks.

**Bug opportunities:** Lost increments, duplicate retractions, reset races,
negative counters, and mutable history escape.

**Discriminating check:** Replay a backtrack while ending its round; counts
remain valid and the frozen snapshot cannot change afterward.

### 73. HarmonicConvergenceReporter -> snapshot forecasting without blocking event ingestion

**Source:** [`data.json:1567`](prompts_from_other_benchmarks/data.json#L1567),
`ClassEval_101`.

**Existing problem:** Solver events change angle/amplitude; Taylor calculations
forecast convergence and completion summarizes history.

**Proposed concurrency implementation:** Serialize event transitions with a
run generation. Capture angle, amplitude, precision, and history for forecasting
under lock, then compute outside it. Cached forecasts include their source
revision and cannot overwrite newer state; reset and ending invalidate pending
publication.

**Bug opportunities:** Mixed forecast inputs, long computation starving updates,
history escape, and stale result after reset.

**Discriminating check:** Hold a forecast while ingesting conflicts and resetting;
event updates proceed and the old forecast cannot attach to the new run.

### 74. TimeGatedFileProxy -> admitted I/O leases and close coordination

**Source:** [`data.json:3019`](prompts_from_other_benchmarks/data.json#L3019),
`ClassEval_194`.

**Existing problem:** Access windows and an overridable clock decide whether
an underlying stream may perform I/O.

**Proposed concurrency implementation:** Admit I/O under the schedule lock,
then execute it outside that lock using a stream lease. Specify whether an
operation admitted before window expiry may finish afterward. Close prevents
new admissions and retires the stream after existing leases drain; clock and
window updates notify an optional blocking API.

**Bug opportunities:** Schedule-check/use races, stream use-after-close,
lease leaks, and blocking I/O under the schedule monitor.

**Discriminating check:** Close while an admitted read is paused; the stream
must not be physically closed before its lease is resolved.

### 75. ClassRegistrationSystem -> capacity enrollment with ordered waitlists

**Source:** [`ClassEval_data.json:1853`](prompts_from_other_benchmarks/ClassEval_data.json#L1853),
`ClassEval_22`.

**Existing problem:** Student registration and course enrollment support
major-based queries and popularity counts.

**Proposed concurrency implementation:** Add capacity, withdrawal, and waitlists.
Atomically maintain course seats and each student's enrollment; withdrawal
promotes the oldest eligible waiter and updates both indexes. Copy enrollment
snapshots and define duplicate registration and waitlist cancellation.

**Bug opportunities:** Over-enrollment, index divergence, double promotion,
mutable lists, and waitlist starvation.

**Discriminating check:** Two students claim the last seat, then its owner
withdraws; exactly one gets it and the correct waiter is promoted.

### 76. Hotel -> room holds, expiry, and check-in ownership

**Source:** [`ClassEval_data.json:3594`](prompts_from_other_benchmarks/ClassEval_data.json#L3594),
`ClassEval_42`.

**Existing problem:** Booking, check-in, check-out, and available-room counts.

**Proposed concurrency implementation:** Add concrete room IDs and expiring
hold tokens. Hold, confirm, check-in, expiry, and release update room ownership
and type counts atomically. A stale check-out token cannot release a room
assigned to a later guest generation.

**Bug opportunities:** Double booking, expired hold confirmation, ownership
escape, and stale release inflating availability.

**Discriminating check:** Expire and reassign a room, then present its old
check-out token; availability and the new ownership stay unchanged.

### 77. MovieBookingSystem -> atomic contiguous-seat groups

**Source:** [`ClassEval_data.json:4926`](prompts_from_other_benchmarks/ClassEval_data.json#L4926),
`ClassEval_59`.

**Existing problem:** Movies expose schedules and seat counts; booking consumes
available seats.

**Proposed concurrency implementation:** Introduce seat arrays and group holds.
Claim an entire contiguous group or none; token-owned confirmation/cancellation
updates availability once. Detach seat-map snapshots, and define an explicit
oldest-eligible policy if groups wait for capacity.

**Bug opportunities:** Overlapping seat claims, partial groups, double release,
mutable arrays, and large-group starvation.

**Discriminating check:** Two groups compete for overlapping seats; one complete
group wins, never two partial successes.

### 78. StockPortfolioTracker -> coherent positions, cash, and quote batches

**Source:** [`ClassEval_data.json:7052`](prompts_from_other_benchmarks/ClassEval_data.json#L7052),
`ClassEval_82`.

**Existing problem:** Buying/selling updates cash and holdings; valuation and
summary aggregate positions.

**Proposed concurrency implementation:** Add atomic quote batches and trade IDs.
Validate funds/holdings and apply each trade once under one protocol. Valuation
uses one captured holdings/cash/quote snapshot; quote retrieval callbacks run
outside locks. Use integer monetary units or a stated rounding contract.

**Bug opportunities:** Double spend, mixed quote epochs, duplicate trades,
position escape, and callbacks blocking trades.

**Discriminating check:** Race two purchases spending the same cash; balances
stay valid and valuation identifies its captured quote revision.

### 79. CalendarUtil -> atomic event rescheduling

**Source:** [`ClassEval_data.json:1377`](prompts_from_other_benchmarks/ClassEval_data.json#L1377),
`ClassEval_17`.

**Existing problem:** Events, availability, upcoming events, and free-slot queries.

**Proposed concurrency implementation:** Add `reschedule(eventId, newSlot,
expectedRevision)`. Check conflicts excluding the event's own old slot, then
replace atomically; failure preserves the old event. Pin snapshots for upcoming/
free-slot computation outside locks and copy event records.

**Bug opportunities:** Remove-then-add visibility gaps, lost events on conflict,
mutable event escape, and inconsistent derived free slots.

**Discriminating check:** Competing reschedules target one slot; rejected
rescheduling must not delete the original appointment.

### 80. Classroom -> atomic multi-room allocation

**Source:** [`ClassEval_data.json:1764`](prompts_from_other_benchmarks/ClassEval_data.json#L1764),
`ClassEval_21`.

**Existing problem:** Course insertion/removal and temporal conflict checks.

**Proposed concurrency implementation:** Add `reserveTogether(otherRoom, slot)`
for courses needing paired facilities. Use immutable room ordering to validate
and insert into both schedules or neither. Preserve the source's touching-endpoint
conflict rule and explicitly handle reserving the same room twice.

**Bug opportunities:** Reciprocal room-lock deadlock, partial reservations,
check-then-insert races, and schedule escape.

**Discriminating check:** Reverse-order paired reservations must terminate;
a conflict in either room leaves both schedules unchanged.

### 81. AssessmentSystem -> published assessment batches and rankings

**Source:** [`ClassEval_data.json:383`](prompts_from_other_benchmarks/ClassEval_data.json#L383),
`ClassEval_4`.

**Existing problem:** Student/course scores drive GPA, failing-student queries,
course averages, and top-student selection.

**Proposed concurrency implementation:** Add atomic score batches and
`rankingSnapshot`. Copy nested score records, update a revision once per batch,
and calculate all report fields from one snapshot outside the monitor.
Document ties and empty-course behavior.

**Bug opportunities:** Mixed partial batches, mutable student records,
stale ranking cache, and long reports blocking grade updates.

**Discriminating check:** Replace a class's scores while calculating a report;
GPA, averages, and ranking must reflect one complete batch revision.

### 82. JobMarketplace -> withdrawal-safe interview admission

**Source:** [`ClassEval_data.json:4145`](prompts_from_other_benchmarks/ClassEval_data.json#L4145),
`ClassEval_49`.

**Existing problem:** Jobs and resumes can be posted, removed, searched,
and matched by requirements.

**Proposed concurrency implementation:** Add stable IDs, revisions, and
`reserveInterview(jobId, candidateId)`. Match on copies outside the lock,
then revalidate both records before reservation. Deletion invalidates handles;
reusing a name must not resurrect an earlier candidate/job identity.

**Bug opportunities:** Stale-match acceptance, ID reuse/ABA, record escape,
and search callbacks under locks.

**Discriminating check:** Withdraw and repost a candidate during matching;
an old match cannot reserve the newly posted generation.

### 83. MahjongConnect -> atomic paired removal on a changing board

**Source:** [`ClassEval_data.json:4506`](prompts_from_other_benchmarks/ClassEval_data.json#L4506),
`ClassEval_54`.

**Existing problem:** Matching tile pairs require a valid connection path;
removing all icons ends the game.

**Proposed concurrency implementation:** `removePair(expectedBoardRevision)`
checks both icons and path on one snapshot, then conditionally removes both.
Keep remaining-count/game-over state coherent; deep-copy board snapshots.
Reset advances the generation and invalidates outstanding pair proposals.

**Bug opportunities:** Half-removal, stale path checks, duplicate tile
consumption, and board escape.

**Discriminating check:** Two removals share one tile; at most one succeeds
and the remaining count changes by exactly two.

### 84. EightPuzzle -> bounded parallel breadth-first frontier

**Source:** [`ClassEval_data.json:2987`](prompts_from_other_benchmarks/ClassEval_data.json#L2987),
`ClassEval_35`.

**Existing problem:** Board moves and breadth-first search produce a solution path.

**Proposed concurrency implementation:** Use immutable board states, an atomic
visited claim, and a shared bounded work frontier. Preserve BFS depth order
with completed-level accounting so parallel completion still returns a shortest
path. Cancellation and goal discovery wake producers/consumers; avoid every
worker blocking while trying to enqueue successors.

**Bug opportunities:** Duplicate visits, corrupt parent chains, frontier
deadlock, and termination detected with work still in flight.

**Discriminating check:** Bound the frontier tightly and compare the resulting
path length with sequential BFS; cancellation must drain blocked workers.

### 85. MinesweeperGame -> atomic flood reveal and terminal outcome

**Source:** [`ClassEval_data.json:4828`](prompts_from_other_benchmarks/ClassEval_data.json#L4828),
`ClassEval_58`.

**Existing problem:** Hidden/player maps, sweeps, score, and winning checks.

**Proposed concurrency implementation:** Add flood reveal, reset generations,
and explicit won/lost state. Reveal/score each cell once despite overlapping
sweeps; commit the terminal outcome under one protocol. Copy both-dimensional
views without exposing hidden arrays.

**Bug opportunities:** Double scoring, mixed reset boards, contradictory
won/lost publication, and nested-array escape.

**Discriminating check:** Concurrent floods overlap and a mine sweep races
completion; scores and the sole terminal outcome follow the chosen order.

### 86. BlackjackGame -> shared-deck dealing and frozen round settlement

**Source:** [`ClassEval_data.json:964`](prompts_from_other_benchmarks/ClassEval_data.json#L964),
`ClassEval_12`.

**Existing problem:** A deck and player/dealer hands determine hand values
and the winner.

**Proposed concurrency implementation:** Add `draw(player, roundId)` and
`settleRound`. Remove a card and append it to one hand atomically; settle
freezes all hands once and rejects later draws for that round. Shuffle/reset
requires a new round generation and cannot alter frozen results.

**Bug opportunities:** Duplicate cards, deck/hand divergence, double
settlement, and mutable hand escape.

**Discriminating check:** Concurrent draws never assign one card twice,
and a draw racing settlement belongs either entirely before it or is rejected.

### 87. Chat -> bounded per-user inbox delivery and recipient removal

**Source:** [`ClassEval_data.json:1678`](prompts_from_other_benchmarks/ClassEval_data.json#L1678),
`ClassEval_20`.

**Existing problem:** Users can join/leave, send messages, and inspect histories.

**Proposed concurrency implementation:** Add bounded inboxes and blocking
delivery. Resolve a stable recipient generation, wait without holding a global
membership lock, and recheck membership after wakeup. Removing a user closes
that inbox and awakens senders; rejoining creates a distinct generation.

**Bug opportunities:** Orphan delivery, membership/inbox lock inversion,
missed close notifications, and exposed message lists.

**Discriminating check:** Remove and rejoin a recipient while delivery waits
for capacity; old delivery cannot silently target the new inbox.

### 88. EmailClient -> size-based admission with unread-message claims

**Source:** [`ClassEval_data.json:3072`](prompts_from_other_benchmarks/ClassEval_data.json#L3072),
`ClassEval_36`.

**Existing problem:** Capacity checks, FIFO cleanup, sending, and fetching the
first unread email.

**Proposed concurrency implementation:** Make size-check/evict/append atomic,
reject oversized messages before eviction, and claim unread status once on
fetch. A no-eviction blocking variant must distinguish fetching from deletion:
marking read alone does not necessarily release bytes.

**Bug opportunities:** Double fetch, incorrect size accounting, oversized
eviction loops, and mutable message escape.

**Discriminating check:** Two fetchers race for one unread message; only one
claims it, and a rejected oversized message leaves existing mail unchanged.

### 89. DatabaseProcessor -> bounded connection leases and shutdown

**Source:** [`ClassEval_data.json:2360`](prompts_from_other_benchmarks/ClassEval_data.json#L2360),
`ClassEval_28`.

**Existing problem:** Table creation and row insertion/search/deletion.

**Proposed concurrency implementation:** Wrap an injected backend in a small
hand-written lease pool. Operations acquire/release leases in `finally`, run
backend work outside the pool monitor, and observe explicit closing/closed
states. Close rejects new acquisitions and waits for or cancels outstanding
leases according to a specified policy.

**Bug opportunities:** Lease leaks, backend use-after-close, waiter starvation,
and blocking query calls under the pool monitor.

**Discriminating check:** A failing query returns its lease; close wakes blocked
acquirers and cannot reuse an in-flight connection.

### 90. ZipFileProcessor -> shared archive lifetime and extraction claims

**Source:** [`ClassEval_data.json:8709`](prompts_from_other_benchmarks/ClassEval_data.json#L8709),
`ClassEval_99`.

**Existing problem:** Opening archives, extracting entries, and creating archives.

**Proposed concurrency implementation:** Use an injected archive backend and
generation-owned entry handles. Claim destination names atomically to prevent
two concurrent extractions overwriting each other. Stream bytes outside the
registry lock; close/replace retires old handles after their leases drain.
Publish completed output manifests only after extraction succeeds.

**Bug opportunities:** Partial manifest publication, duplicate extraction
claims, handle escape, and close/read races.

**Discriminating check:** Extract one entry twice while closing the archive;
destination ownership and archive lifetime remain unambiguous.

### 91. WeatherSystem -> single-flight city lookup with cache invalidation

**Source:** [`ClassEval_data.json:8471`](prompts_from_other_benchmarks/ClassEval_data.json#L8471),
`ClassEval_96`.

**Existing problem:** Current city selects weather/temperature data and
queries convert units.

**Proposed concurrency implementation:** Add an injected slow provider and
revisioned city selection. Coalesce identical city lookups, fetch outside
locks, and cache results under their captured city generation. Unit conversion
uses the returned city's temperature snapshot, not current mutable global fields.

**Bug opportunities:** Wrong-city stale completion, mixed units/temperature,
duplicate loads, and failure-stranded waiters.

**Discriminating check:** Switch cities during a lookup; the old response cannot
populate the new city's selected-state cache.

### 92. ShoppingCart -> frozen checkout with retry-safe completion

**Source:** [`ClassEval_data.json:6377`](prompts_from_other_benchmarks/ClassEval_data.json#L6377),
`ClassEval_75`.

**Existing problem:** Items, quantities, prices, removal, and totals.

**Proposed concurrency implementation:** Add checkout IDs and
`EDITABLE`, `PROCESSING`, `COMPLETED`, `FAILED` states. Freeze copied line items
and total before invoking an external checkout backend. Retrying the same ID
returns the same outcome; explicitly define whether failure restores editing
and how later attempts distinguish prior work.

**Bug opportunities:** Duplicate local checkout, post-freeze mutation, array
escape, and callback deadlock.

**Discriminating check:** Concurrent retries invoke the backend once per
locally coalesced checkout ID and return one coherent result.

### 93. BookManagement -> atomic batch inventory with sorted snapshots

**Source:** [`ClassEval_data.json:1032`](prompts_from_other_benchmarks/ClassEval_data.json#L1032),
`ClassEval_13`.

**Existing problem:** Adding/removing books maintains a title-sorted inventory
and quantity queries.

**Proposed concurrency implementation:** Add an all-or-nothing batch delta API.
Validate every resulting quantity before committing; preserve sorted key
structure while updates occur. Return copied inventory records; perform
presentation sorting on a snapshot rather than mutate backing storage.

**Bug opportunities:** Partial batches, negative stock, sorted-index
corruption, and live inventory escape.

**Discriminating check:** One invalid delta rejects the entire batch, and
concurrent snapshots never observe only its successful prefix.

### 94. AutomaticGuitarSimulator -> ordered score playback and cancellation

**Source:** [`ClassEval_data.json:499`](prompts_from_other_benchmarks/ClassEval_data.json#L499),
`ClassEval_5`.

**Existing problem:** Score interpretation creates chord/tune output.

**Proposed concurrency implementation:** Add queued score generations,
`playNext`, `replaceScore`, and `stop`. Parse scores privately, publish immutable
chord events, and claim each event once in score order. Invoke an output
listener outside locks; replacing/stopping invalidates old pending events
without corrupting the next score's cursor.

**Bug opportunities:** Duplicate/skipped chords, stale output after stop,
score-array escape, and listener deadlock.

**Discriminating check:** Pause output, replace the score, and resume; old
completion cannot advance the new score's playback cursor.

### 95. StripedMap -> global operations across independently locked buckets

**Source:** [`problemlist.csv:304-314`](prompts_from_other_benchmarks/problemlist.csv#L304),
topic "Hash-based map using lock striping."

**Existing problem:** Buckets use a fixed stripe mapping; lookup and clear
synchronize on appropriate stripes.

**Proposed concurrency implementation:** Add put/remove and define whether
clear is atomic or incremental. For an atomic variant, acquire stripes in one
fixed order and use the same protocol for snapshots and resize. Keep node
records private; a resized table must retain a consistent bucket/stripe mapping.

**Bug opportunities:** Wrong monitor selection, multi-stripe deadlock,
unsafe table publication, and node escape.

**Discriminating check:** Race global clear/snapshot with writes; their observed
semantics must match the explicitly selected atomic or incremental contract.

### 96. PublishingVehicleTracker -> safe live handles versus deep snapshots

**Source:** [`problemlist.csv:155-165`](prompts_from_other_benchmarks/problemlist.csv#L155),
topic "Vehicle tracker that safely publishes underlying state."

**Existing problem:** A tracker exposes a read-only map with thread-safe mutable
point objects and permits coordinate updates.

**Proposed concurrency implementation:** Hand-write the storage and distinguish
`getLiveHandle` from `snapshot`. A live handle synchronizes coordinate pairs;
an immutable snapshot deeply copies pairs at one tracker revision. Added
remove/reinsert operations need generations so obsolete handles cannot modify
new vehicles.

**Bug opportunities:** Torn x/y pairs, unsafe point publication, shallow
snapshot escape, and stale handle aliasing.

**Discriminating check:** Reinsert a vehicle while retaining its old handle;
old updates cannot affect the new vehicle, and snapshots remain unchanged.

### 97. Task timing gates -> reusable failure-aware task cohort

**Source:** [`problemlist.csv:177-187`](prompts_from_other_benchmarks/problemlist.csv#L177),
topic "Using CountDownLatch for starting and stopping threads in timing tests."

**Existing problem:** Start/end gates coordinate task timing and completion.

**Proposed concurrency implementation:** Replace library latches with a
`TaskCohort` monitor protocol exposing register, start, complete, fail, await,
and reset. Completion decrements once per participant, including failures;
generation tokens reject late completion from previous runs. Execute submitted
tasks outside the coordinator monitor.

**Bug opportunities:** Lost completion, double decrement, generation mixing,
failure-stranded waiters, and task callback deadlock.

**Discriminating check:** A task throws and reports completion late after reset;
both runs' remaining counts and terminal results stay correct.

### 98. SafeListener -> publication and unregister lifecycle control

**Source:**
[`problemlist.csv:35-45`](prompts_from_other_benchmarks/problemlist.csv#L35).

**Existing problem:** A private constructor and static factory register an
event listener only after constructing its owning object, avoiding premature
`this` escape.

**Proposed concurrency implementation:** Retain that safe-construction pattern
and add `close`/unregister plus a mutable event counter and snapshots. The event
source is permitted to invoke the listener synchronously during registration.
Specify whether close only prevents new admissions or also waits for admitted
callbacks. A callback-initiated close must not wait for itself.

**Bug opportunities:** Constructor escape, races between callback admission and
close, unsafe event-state access, external registration under an internal lock,
and self-deadlock during close.

**Discriminating check:** Registration immediately invokes a callback that reads
initialized fields. It must observe complete state. Reentrant close must
terminate under the documented lifecycle policy.

**Role:** Less novel than the top entries, but a focused escape/publication
control that can help interpret analyzer coverage.

### 99. Preloader -> hand-written single-assignment result lifecycle

**Source:**
[`problemlist.csv:188-224`](prompts_from_other_benchmarks/problemlist.csv#L188).

**Existing problem:** `FutureTask` wraps background loading; `start()` begins
work and `get()` waits for the value or propagates a loading failure.

**Proposed concurrency implementation:** Replace `FutureTask` with explicit
states `NEW`, `RUNNING`, `SUCCESS`, `FAILED`, and `CANCELLED`; expose `start`,
`get`, and `cancel`. Multiple `start` calls launch at most one loader. Run
the loader outside the monitor, publish value or failure before the terminal
state becomes observable, and notify waiters on every terminal outcome.
Specify `get` before start and logical versus cooperative cancellation.

**Bug opportunities:** Double start, result/status publication mismatch,
failure stranding waiters, cancellation overwritten by completion, and waiting
while holding a lock needed by the loader.

**Discriminating check:** Race two starts and cancellation with completion.
There is one load invocation, one terminal outcome, and every waiter receives
the outcome prescribed by the winning state transition.

**Role:** A useful lifecycle baseline, provided the LLM implements the state
machine rather than delegating the entire problem to a library future.

### 100. BetterAttributeStore -> slow predicate outside shared-state locks

**Source:**
[`problemlist.csv:479-489`](prompts_from_other_benchmarks/problemlist.csv#L479).

**Existing problem:** Read a user's location under synchronization, then test
it against a regular expression; absent locations return false.

**Proposed concurrency implementation:** Add updates and an injected
`LocationPredicate`. Capture the immutable location and its revision under the
monitor, then evaluate the predicate outside it. A stricter
`matchCurrentAndAct` variant must revalidate the revision before committing
an action; ordinary snapshot matching need not retry. The callback can block
or call back into the store.

**Bug opportunities:** Slow/blocked predicate under the state lock, callback
deadlock, mutable attribute escape, unlocked map accesses, and stale validation
used for a conditional mutation.

**Discriminating check:** Hold predicate evaluation on a latch while another
thread updates the store. The update must finish; a version-conditional action
must reject the captured value if its revision became stale.

**Role:** A small lock-duration baseline. Its progress defect is useful for
checker calibration, but an injected slow predicate is not automatically
recognized as starvation by the analyzer.

## Choosing a compact first benchmark suite

| Candidate | Main reason to include | Static-analysis emphasis | Additional behavioral oracle |
| --- | --- | --- | --- |
| HierarchicalScheduler | Cross-object reservation invariant | Lock ordering, publication, racy hierarchy access | Parent/child exclusivity and admission policy |
| ThermostaticBuildOrchestrator | Adaptive limits and slow work | Shared counters, lock scope, blocking call chains | Capacity release, hysteresis, eligible-job progress |
| ResolvingVendingMachine | Isolation and transaction-owned rollback | Shared ledger/undo accesses, callback lock scope | Conservation and rollback isolation |
| HarmonicBufferedStream | Callback, read, and close lifecycle | Escape/publication, locking, blocking source reads | Exactly-once completion and reentrancy |
| UserSpaceProvisioner | Slow initialization and atomic publication | Manifest/status access and installer lock scope | Single-flight execution and stale-attempt rejection |
| VersionedSlotManager | Conditional multi-field commit | Inconsistent field locking and representation exposure | Linearizable version checks and overlap rejection |
| TaggedCapacityBuffer | Predicate waiting and two-object transfer | Queue/accounting accesses and lock ordering | Unique delivery, transfer conservation, fairness |
| AdaptiveTrustVault | State-dependent validity | Counter/store synchronization and snapshot exposure | Consistent metric/TTL decision order |

Add **SafeListener, Preloader, and BetterAttributeStore** as narrower controls.
They help distinguish poor analyzer coverage from failure on the more complex
domain adaptations.

For each prompt, require all public operations to support concurrent callers,
define its linearization and close/cancel behavior, and state who owns every
input/output object. Provide immutable nested record types or require defensive
copies so that thread safety is not accidentally defeated by caller mutation.
Allow a harness to create genuinely overlapping calls; the small `main` programs
and thread caps in the CSV are not sufficient correctness or progress tests.

Avoid prescribing the exact locking implementation in every prompt: specify
invariants and allowed primitives, then let the LLM choose a correct strategy.
Use a separate fine-grained/progress variant where a single global lock is not
acceptable. Otherwise a coarse monitor around fast in-memory operations may be
entirely correct, and should not be penalized merely for being simple.

## Lower-priority material

The mutable integer holder, simple counter, basic confinement examples, and
immutable name collection in the CSV are useful smoke tests but weak choices
for novel, difficult class synthesis. Pure calculators, converters, and text
algorithms in ClassEval often have little unavoidable shared state; adding a
generic synchronized cache does not make them distinct concurrency challenges.

Several synthesized classes repeat the same pattern under different names:
versioned inventories/orders, TTL stores, authenticated containers, and
scheduled builders. The 100 entries deliberately include some controlled
variants, but their proposed invariants differ: allocation transfer preserves
an absolute deadline, ranking updates extend expiry, and multi-party retrieval
depends on a presence predicate. Sample one representative per synchronization
challenge for a compact suite, or report family-level results rather than
counting every domain variant as an independent novel benchmark.
`VersionedInventoryLedger` (`data.json:3175`, `ClassEval_204`) is an unranked
alternative to VersionedSlotManager, not a 101st recommendation.

Do not adopt the Python-specific typing, platform-path, packaging, or numerical
machinery solely to inflate difficulty. The strongest conversions preserve the
domain's shared-state constraints while replacing incidental infrastructure
with bounded records and explicit, testable interfaces.

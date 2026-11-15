
novel_problems = [
    {
        "name": "HierarchicalScheduler",
        "source": "prompts_from_other_benchmarks/data.json#ClassEval_228",
        "contract": """Contract:
- A scheduler instance represents a node in a dynamically growing reservation
  tree. Provide a root constructor, child(String name), reserve(long start,
  long end), cancel(long reservationId), and snapshot(). Define detached
  reservation and snapshot types in the same file.
- child returns the unique child of this node with that nonempty name,
  creating it if needed. Concurrent calls for the same name must return the
  same fully initialized node. Nodes are never removed or reparented.
- Reservations use half-open intervals [start, end), with start < end.
  A reservation conflicts with overlapping reservations on this node, any
  ancestor, or any descendant. Reservations on separate sibling subtrees
  do not conflict. Touching endpoints do not overlap.
- reserve atomically checks the entire relevant tree and either commits one
  reservation with a tree-wide unique positive ID or returns -1 on conflict.
  It does not wait for availability. cancel removes only a reservation owned
  by this node and returns whether it existed.
- snapshot returns this subtree's node paths and reservations from one
  coherent observation. Returned arrays and records must not expose live
  tree state. A single tree-wide coordinator monitor is permitted.
- Invalid names or intervals throw IllegalArgumentException without mutation.
  Concurrent parent/child reservations for overlapping intervals must never
  both succeed; do not hold node locks in inconsistent ancestor/child order.""",
    },
    {
        "name": "ThermostaticBuildOrchestrator",
        "source": "prompts_from_other_benchmarks/data.json#ClassEval_143",
        "contract": """Contract:
- Construct with positive queue capacity and normal concurrency limit, and
  thresholds 0 <= low < high <= 100. Define a Job interface with void run()
  and modes NORMAL and THROTTLED in this file. THROTTLED has admission limit
  1; NORMAL uses the constructor's limit.
- Provide submit(Job), cancel(long jobId), updateLoad(int load),
  overrideMode(Mode mode), clearOverride(), runNext(), close(), and snapshot().
  submit returns a unique positive ID, throws IllegalStateException if closed
  or the pending queue is full, and rejects null jobs.
- Automatic mode starts NORMAL, changes to THROTTLED at load >= high,
  changes back at load <= low, and otherwise retains its previous mode.
  updateLoad accepts 0..100 and updates automatic mode even during an override.
  A manual override wins until clearOverride restores the current automatic
  mode. These updates and the effective admission limit are atomic.
- runNext waits interruptibly until the oldest pending job can be admitted.
  Claim exactly one job and increment running count atomically; invoke its
  callback outside all state monitors and release running capacity in finally.
  Return true if a job ran, false if closed and drained. Propagate callback
  RuntimeException after releasing capacity. Admission order is FIFO for
  jobs, not for competing worker threads.
- Lowering the limit must not cancel running jobs; it blocks new admissions
  until running count is below the new limit. cancel removes pending jobs
  only and returns whether removal occurred. close rejects new submissions
  but lets queued/running jobs drain. Wake affected waiters on every relevant
  transition, including close, cancellation, and callback failure.
- snapshot coherently reports modes, pending IDs, running count, limit, and
  closed status using detached records. Callbacks may reenter snapshot,
  updateLoad, cancel, or close without deadlock. No background threads are
  started by the constructor.""",
    },
    {
        "name": "ResolvingVendingMachine",
        "source": "prompts_from_other_benchmarks/data.json#ClassEval_170",
        "contract": """Contract:
- Construct with a copied array of nonnegative integer prices, one per product.
  All money is long-valued integer units; funds are machine-wide, not per-user.
  Provide restock(int product, int quantity), insertFunds(long amount),
  purchaseBundle(long transactionId, int[] quantities), cancel(long
  transactionId), and snapshot(). Define a Dispenser interface whose
  boolean dispense(long transactionId, int[] quantities) callback is injected
  in the constructor. Stock and funds start at zero.
- A bundle contains one nonnegative quantity per product and at least one
  positive quantity. Reject invalid inputs and arithmetic overflow before
  mutation. Transaction IDs must be positive and single-use, including after
  failure; duplicate IDs throw IllegalArgumentException.
- Atomically register the transaction, validate all available stock and funds,
  and reserve its complete bundle and price. Unavailable bundles return false
  without changing stock or funds. Other transactions cannot spend reserved
  resources. Keep an independent reservation record per transaction.
- Invoke dispense outside the state monitor, passing a detached copy.
  A true result commits unless cancellation already won; false or a callback
  RuntimeException rolls back. Propagate the exception after rollback.
  purchaseBundle returns true only for a committed purchase.
- cancel atomically rolls back a still-pending reservation once and returns
  true; otherwise returns false. Cancellation is logical: a callback already
  running may finish, but its late result must not recommit or refund again.
  Rolling back adds only that transaction's reservations back, preserving
  intervening restocks, deposits, and other purchases. Account for reserved
  resources when checking overflow in restock and insertFunds.
- snapshot returns coherent detached available stock, available funds, and
  transaction statuses. Caller mutation must not alter prices, bundles, or
  internal records. A dispenser may reenter snapshot, restock, or cancel.""",
    },
    {
        "name": "HarmonicBufferedStream",
        "source": "prompts_from_other_benchmarks/data.json#ClassEval_290",
        "contract": """Contract:
- Define ChunkSource with byte[] read() and void close(), and
  CompletionListener with void complete(Result result), in the same file.
  Construct with these dependencies without publishing this or invoking them.
  read returns null at EOF; a nonnull chunk must be copied before retention.
- Provide readChunk(), close(), and snapshot(), and explicit states OPEN,
  READING, COMPLETING, CLOSED, and FAILED. Only one source read may be in
  flight. Concurrent readChunk callers wait interruptibly while another read
  is running. Invoke source methods and the listener outside state monitors.
- Buffer chunks in source order. Maintain byte count and a phase equal to
  (sum of unsigned byte values modulo 360), atomically with buffer updates.
  snapshot returns a copied buffer, byte count, phase, and current state.
- EOF and explicit close compete for one completion claim. close is
  nonblocking if a read is in flight: record a close request, prevent further
  reads, and let that reader finish. Retain its successful chunk before
  completion. If idle, close performs completion itself. Repeated or reentrant
  close during COMPLETING or a terminal state is a no-op.
- Completion invokes source.close exactly once, then the listener exactly
  once with a detached Result containing buffered bytes, count, phase, and
  any read/close failure. A close failure must not suppress the listener.
  Even empty streams complete. Clear retained callback references afterward.
- Read or close RuntimeException marks the terminal state FAILED and is
  rethrown by the completing caller after the listener runs. A listener
  RuntimeException also marks FAILED and is propagated; when multiple errors
  occur preserve the earlier error and attach later ones as suppressed.
  Publish CLOSED or FAILED and notify waiters even if callbacks fail.
- readChunk returns a detached chunk, or null after completion. It must not
  wait during COMPLETING, so a listener can reenter readChunk (returning null),
  snapshot, or close without deadlock. Interruption of a waiting reader must
  not strand the active read or claim completion.""",
    },
    {
        "name": "UserSpaceProvisioner",
        "source": "prompts_from_other_benchmarks/data.json#ClassEval_0",
        "contract": """Contract:
- Define an Installer interface with String[] install(String user,
  String[] configuration). Represent installation as this injected callback,
  not real filesystem operations. Provide register(String user, String[]
  configuration), activate(String user), reinstall(String user, String[]
  configuration), verify(String user), and snapshotMetrics().
- Registration creates a PENDING user with copied configuration. Reject null
  or empty usernames, null configuration or entries, and duplicate users.
  Reject unknown users in activation, reinstall, and verification.
- User states are PENDING, INSTALLING, ACTIVE, and FAILED. Each user has a
  monotonically increasing generation. activate claims a PENDING generation,
  invokes install outside shared monitors, and publishes ACTIVE together with
  a copied nonnull manifest only when that generation is still current.
- Concurrent activate callers for an INSTALLING generation join that attempt,
  waiting interruptibly for its result instead of starting duplicate installs.
  ACTIVE returns a copied manifest immediately. FAILED throws the retained
  attempt failure until reinstall. A callback RuntimeException or invalid
  manifest atomically publishes FAILED and wakes all joined callers.
- reinstall replaces copied configuration, advances the generation, invalidates
  the old manifest, and starts a new installation. It may supersede an older
  in-flight attempt. A superseded attempt must never overwrite newer state;
  its joined callers receive IllegalStateException indicating supersession.
  Check revision overflow before mutation.
- verify returns a detached status/generation/manifest snapshot; ACTIVE must
  never be visible without its complete manifest. snapshotMetrics atomically
  reports counts in each state and total current users.
- A slow installer for one user must not block registration or activation of
  other users. Installer callbacks may reenter verify and snapshotMetrics;
  callbacks must not recursively activate/reinstall their own user. Every
  success, failure, or supersession must wake relevant waiters.""",
    },
    {
        "name": "VersionedSlotManager",
        "source": "prompts_from_other_benchmarks/data.json#ClassEval_26",
        "contract": """Contract:
- Provide getSnapshot(), reserve(long start, long end, long expectedRevision),
  and cancel(long reservationId, long expectedRevision). Start at revision 0
  with no reservations. Define detached Slot, Snapshot, and mutation-result
  types in this file.
- Slots are closed intervals [start, end], with start < end; touching endpoints
  conflict. A snapshot contains copied slots and the exact revision captured
  with them under one synchronization protocol.
- reserve compares the expected revision, checks all overlaps, assigns a
  unique positive ID, inserts the slot, and advances revision as one atomic
  conditional write. Return an explicit SUCCESS, STALE_REVISION, or CONFLICT
  status, the observed/resulting revision, and the assigned ID on success.
- cancel similarly compares revision and removes a slot atomically; report
  SUCCESS, STALE_REVISION, or NOT_FOUND. A stale revision takes precedence
  over conflict/not-found checks. Failed writes do not advance revision.
- Revisions and IDs must never be reused or wrap; throw IllegalStateException
  before a successful write would overflow. Returning to an earlier set of
  slots does not restore its revision. Invalid intervals or negative expected
  revisions throw IllegalArgumentException without mutation.
- Two callers using the same revision for different non-overlapping additions
  must not both succeed. Callers cannot mutate retained slots through snapshots.
  Operations are nonblocking apart from synchronization.""",
    },
    {
        "name": "TaggedCapacityBuffer",
        "source": "prompts_from_other_benchmarks/data.json#ClassEval_165",
        "contract": """Contract:
- Construct with a positive byte capacity. Messages have unique IDs, copied
  byte[] payloads, and copied String[] tags. Reject empty payloads, null inputs,
  and payloads larger than capacity. Occupancy is the sum of payload lengths.
  Provide offer(byte[] payload, String[] tags, String[] evictionTags),
  putBlocking(byte[] payload, String[] tags), takeMatching(String tag),
  moveTo(TaggedCapacityBuffer destination, String tag), close(), and snapshot().
- offer is nonblocking. Evict oldest messages sharing any eviction tag first,
  then remaining oldest messages, until the new message fits; insert it and
  return its ID. putBlocking instead waits interruptibly for actual capacity
  and never evicts. Successful insertions preserve FIFO message order.
- takeMatching waits interruptibly for the oldest message bearing that exact
  tag, removes it, reclaims its capacity, and returns a detached message.
  Each message may be delivered only once even with multiple matching readers.
- moveTo atomically moves this buffer's oldest matching message to destination,
  preserving its identity and copies. Return false without mutation if none
  matches, either buffer is closed, or destination lacks capacity. Never evict
  or wait for capacity during transfer. Reject destination == this. Use a
  deadlock-free two-buffer synchronization protocol for opposite moves.
- close rejects future insertions with IllegalStateException and wakes all
  waiters; existing messages may drain. takeMatching returns null when closed
  and no matching message remains. A blocked producer awakened by closure
  throws IllegalStateException. Interrupted waits leave no partial message.
- snapshot coherently returns detached ordered messages, occupancy, capacity,
  and closed status. Transfers must notify affected waiters at both ends;
  no lost/duplicated messages or incorrect byte accounting are permitted.
  FIFO concerns message selection, not scheduling fairness among threads.""",
    },
    {
        "name": "AdaptiveTrustVault",
        "source": "prompts_from_other_benchmarks/data.json#ClassEval_192",
        "contract": """Contract:
- Construct with a positive base TTL and an injected Clock interface with
  long now(). The clock is thread-safe, nonnegative, monotonic, and nonblocking.
  Provide deposit(String key, String credential, byte[] payload),
  attemptRetrieve(String key, String credential), cleanup(), and snapshotMetrics().
  Reject null inputs and copy payloads before retaining or returning them.
- An entry stores credential, payload, and deposit time; depositing the same
  key atomically replaces it. Effective TTL applies retroactively to all
  entries and is baseTTL * F1, where F1 = 2*TP / (2*TP + FP + FN), or 1.0
  if the denominator is zero. Avoid integer overflow in metric arithmetic.
- Each retrieval is one atomic decision with this exact order: capture time;
  determine whether an entry is alive using the pre-attempt TTL (age < TTL);
  classify/update counters; recompute TTL; recheck the same entry using the
  new TTL; then return a copy only if credentials matched and it is still alive.
- Alive plus matching credential is TP; alive plus wrong credential is FN;
  absent/expired plus a nonempty supplied credential is FP; absent/expired
  plus an empty supplied credential is TN. Each completed attempt increments
  exactly one counter, even if the new TTL then prevents a TP's delivery.
  Empty credentials are not permitted in deposits. Expired entries encountered
  by retrieval are removed and not resurrected if TTL later grows.
- cleanup uses one captured time and one current metric/TTL state to remove
  expired entries atomically. snapshotMetrics returns coherent TP, FP, FN, TN,
  F1, effective TTL, and stored-entry count without exposing payloads.
- Wrong credentials return null without disclosing stored credentials;
  they must never return a payload. Concurrent retrieval and cleanup must not
  combine decisions from different metric versions or lose counter updates.
  Detect exhausted counters before mutation and fail explicitly.""",
    },
    {
        "name": "SecureMetricAggregator",
        "source": "prompts_from_other_benchmarks/data.json#ClassEval_108",
        "contract": """Contract:
- Construct with an injected Clock interface with long now(), assumed
  thread-safe, nonnegative, monotonic, and nonblocking. Provide put(double[]
  measurements, long ttl), removeExpired(), and statistics(). Reject null,
  empty, NaN, or infinite measurements and nonpositive TTL.
- put copies the measurements and stores one batch with expiration now + ttl;
  reject timestamp overflow before mutation. Each value in a batch contributes
  independently. Values are expired when expiration <= observation time.
- removeExpired atomically removes expired batches using one captured clock
  value and returns the number of individual measurements removed.
- statistics captures one clock value, purges expired batches, and copies all
  remaining values under a monitor. Release the monitor before sorting or
  performing expensive statistical calculations; writers must remain able
  to progress during that work.
- Return a detached immutable Statistics record containing observation time,
  count, arithmetic mean, median, and population variance for that exact
  captured dataset. Median for even counts is the mean of the two middle
  values. Empty results have count 0 and NaN for all three statistics.
  Use numerically stable calculations where possible and never sort a live
  internal array.
- Concurrent insertion or expiration during computation must not change any
  returned statistic's dataset. Caller mutation of an input array must not
  change stored values. Do not cache results using an unsynchronized version
  check or expose internal batches.""",
    },
    {
        "name": "TemporalForexCache",
        "source": "prompts_from_other_benchmarks/data.json#ClassEval_19",
        "contract": """Contract:
- Define an injected Clock interface with long now(), assumed thread-safe,
  nonnegative, monotonic, and nonblocking. Rates are positive finite doubles
  representing units of each currency per one unit of a common base.
  Provide updateRate(String currency, double rate, long ttl),
  updateRatesBatch(String[] currencies, double[] rates, long ttl),
  convert(String from, String to, double amount), prune(), and snapshot().
- Reject null/empty currency names, duplicate batch names, mismatched/empty
  arrays, invalid rates, negative or nonfinite amounts, and nonpositive TTL.
  Copy batch inputs, validate the entire batch, and reject expiration/revision
  overflow before mutation. No invalid batch may be partially applied.
- Updates atomically replace rates and expirations; a whole batch commits at
  one observation time and advances a monotonically increasing cache revision
  exactly once. Unmentioned currencies remain unchanged.
- convert captures both immutable rates, expirations, the cache revision, and
  one observation time under one synchronization protocol. A rate is valid
  only when expiration > observation time. Missing/expired rates cause
  IllegalStateException; same-currency conversion still requires a valid rate.
  Calculate amount / fromRate * toRate outside the monitor and return a
  detached Conversion record containing value, revision, and observation time.
- prune removes expired rates at one captured time, returns the removed count,
  and advances revision once only if removal occurs. snapshot returns copied
  entries and one coherent revision/observation time without pruning.
- A batch alternating A=1,B=2 with A=2,B=4 must always convert one A to two B,
  never a mixture of batches. Pruning must not remove a concurrent replacement.
  No reader may observe partially initialized rate/expiry records.""",
    },
    {
        "name": "BitmaskedTimelineStage",
        "source": "prompts_from_other_benchmarks/data.json#ClassEval_255",
        "contract": """Contract:
- Define an injected Timeline interface with void report(Event event).
  Provide success(String message), warning(String message), failure(String
  message), skip(String message), log(), dispatchNext(), and snapshot().
  Do not invoke the timeline or publish this during construction.
- Use bits INITIAL=2, SUCCESS=4, WARNING=8, FAILURE=16, SKIPPED=32, LOGGED=64.
  Start with INITIAL, empty message, label INITIAL, and revision 0. Outcomes
  are replaceable: each outcome call replaces the exclusive outcome bit,
  message, and matching uppercase label, retaining any LOGGED bit.
- log atomically sets LOGGED and enqueues a detached Event containing the
  resulting mask, message, label, and revision. Every outcome call and log
  advances revision exactly once, even if values repeat. Reject null messages
  and revision overflow before mutation. Outcome calls do not enqueue events.
- dispatchNext claims the oldest queued event only if no report is in flight,
  invokes report outside the state monitor, and returns true. Return false
  immediately if the queue is empty or another dispatch is active. Only one
  callback may run at a time, preserving event order across dispatchers.
- A claimed event is consumed once even if report throws RuntimeException.
  Release the dispatch claim in finally and propagate the exception; subsequent
  events remain dispatchable. No background dispatcher is started.
- snapshot coherently returns mask, message, label, revision, copied pending
  events, and dispatch-active status. The callback may reenter all public
  operations; reentrant dispatchNext returns false rather than waiting.
  Caller mutation of returned records must not alter state or queued events.""",
    },
    {
        "name": "InventoryTransactionBuffer",
        "source": "prompts_from_other_benchmarks/data.json#ClassEval_87",
        "contract": """Contract:
- Construct with a copied nonempty long[] of nonnegative committed quantities.
  Products are array indexes. Provide stage(String ownerId, long[] delta,
  String[] tags), commit(String ownerId, long generation, String[] required,
  String[] forbidden), discard(String ownerId, long generation), and snapshot().
  There is exactly one staging slot shared by all callers, initially empty.
- stage validates and copies a delta with one signed quantity per product
  and copied tags, then atomically replaces any previous staged operation.
  Return a unique positive, monotonically increasing generation. Reject null
  inputs, empty owner IDs, null/empty tags, invalid array lengths, and any
  projected quantity below zero or above Long.MAX_VALUE before replacing.
  Treat tags as sets using exact case-sensitive equality.
- commit checks owner and generation before inspecting the current staged
  tags. Return false without mutation if no matching slot exists, any required
  tag is absent, or any forbidden tag is present. Otherwise atomically apply
  the entire delta to committed quantities and clear the staging slot.
  Empty constraint arrays are allowed; invalid constraints throw
  IllegalArgumentException without mutation.
- discard clears only the slot with the matching owner and generation and
  returns whether it did so. Stale callers must never apply or clear a newer
  owner's operation. A generation is never reused, including after discard.
- snapshot returns coherent copied committed quantities, projected quantities
  (committed plus the current delta, or committed if empty), and detached
  staging metadata. No input or returned array may expose retained state.
  Reject nonpositive generation arguments and exhausted generation counters
  explicitly. Operations are nonblocking apart from synchronization.""",
    },
    {
        "name": "ChatSessionStream",
        "source": "prompts_from_other_benchmarks/data.json#ClassEval_145",
        "contract": """Contract:
- Provide addUser(String user), removeUser(String user), send(String user,
  String text), write(String command), read(int maxChars), seek(int position),
  close(), and snapshot(). Start with no users, empty history, cursor 0,
  and an open session. Usernames are nonempty and contain no colon, CR, or LF;
  message text may be empty but contains no CR or LF. Reject null inputs.
- addUser and removeUser return whether membership changed. Removing a user
  does not delete accepted history; adding that name again is permitted.
  send requires a currently registered user, otherwise throws
  IllegalArgumentException. Each accepted message receives a unique positive
  sequence and atomically appends one structured record and exactly
  user + ": " + text + "\\n" to the text history.
- write accepts exactly the form user + ": " + text, splitting at the first
  colon-space delimiter, and delegates to the same atomic send semantics.
  send and write return the assigned sequence. Reject malformed commands
  and exhausted sequence numbers without modifying either history.
- All reads share one cursor. read requires maxChars > 0, atomically returns
  up to that many available Java characters and advances the cursor by that
  count. Return an empty string at the current end; never wait for messages.
  seek accepts positions from 0 through current history length inclusive.
  Concurrent reads without intervening seeks consume disjoint ranges.
- close is idempotent and atomically rejects subsequent membership changes,
  send, and write with IllegalStateException. Previously accepted history
  remains readable and seekable. No message may appear in only one history.
- snapshot coherently returns copied users, all ordered message records,
  complete text, cursor, final/current sequence, and closed status. Neither
  snapshots nor user removal may expose or mutate retained history.""",
    },
    {
        "name": "GomokuVersionState",
        "source": "prompts_from_other_benchmarks/data.json#ClassEval_146",
        "contract": """Contract:
- Construct with board size >= 5. Provide makeMove(char player, int row,
  int column, long expectedRevision), reset(), and snapshot(). Start with
  an empty square board, X to move, move count 0, no winner, generation 0,
  and revision 0. Use X and O as players and '.' for empty cells.
- Reject invalid players, out-of-bounds coordinates, and negative revisions
  with IllegalArgumentException without mutation. makeMove atomically checks
  revision, terminal state, turn, and cell occupancy, in that order, returning
  STALE_REVISION, FINISHED, WRONG_TURN, or OCCUPIED on failure.
- A successful move places one stone, increments move count, detects five
  or more contiguous equal stones horizontally, vertically, or diagonally,
  and advances revision once. Set winner on victory; a full board without
  a winner is a draw. Advance the current player only for nonterminal moves.
  Return a detached result with status, revision, generation, and winner.
- Publish board, turn, winner/draw status, count, and version string together.
  The display version is size + "." + moveCount + "." + the decimal ASCII
  value of currentPlayer, with ".WIN_X", ".WIN_O", or ".DRAW" appended in
  terminal states. This string is display metadata, not a concurrency token.
- reset atomically clears the board and restores the starting turn/count,
  advances both generation and revision once, and returns a deep snapshot.
  Never reset or reuse the revision counter. Detect revision/generation
  overflow before mutation; failed moves do not consume revisions.
- snapshot returns a deep-copied board and all metadata from one observation.
  Two moves using one expected revision cannot both succeed, and a revision
  captured before reset must never authorize a move on the new board.""",
    },
    {
        "name": "ThermostaticCircuitStream",
        "source": "prompts_from_other_benchmarks/data.json#ClassEval_32",
        "contract": """Contract:
- Construct with a positive long heat threshold. Provide writeWhenCool(byte[]
  data), readWhenReady(int maxBytes), cool(long amount),
  setThreshold(long threshold), close(), and snapshot(). Start with empty
  append-only storage, shared read cursor 0, heat 0, and mode NOMINAL.
- Each written byte adds one heat unit. Writes are all-or-nothing and copy
  their inputs before waiting. Reject null/empty data, nonpositive maxBytes
  or thresholds, and negative cooling amounts without mutation. A payload
  larger than the current threshold is rejected with IllegalArgumentException,
  including if a threshold change makes a waiting write permanently too large.
- writeWhenCool waits interruptibly while overheated or while the entire
  payload would exceed the threshold. Atomically append and add heat when
  eligible. Heat equal to or greater than threshold means OVERHEATED;
  otherwise the mode is NOMINAL. Never overflow heat arithmetic.
- readWhenReady waits interruptibly until unread bytes exist and mode is
  NOMINAL, then atomically consumes up to maxBytes through the shared cursor,
  returning a copied byte array. Reads generate no heat. Concurrent reads
  cannot consume the same bytes twice.
- cool subtracts down to zero; setThreshold replaces the threshold. Both
  recompute mode and wake affected readers/writers without waiting for I/O.
  close permanently sets CLOSED and wakes all waiters. Closed writes throw
  IllegalStateException; closed reads drain unread bytes regardless of heat,
  then return null. cool and setThreshold after close throw
  IllegalStateException and cannot reopen the stream.
- Waiting operations release the state monitor and recheck all predicates.
  Interrupted waits leave buffer, cursor, and heat unchanged. snapshot returns
  coherent copied storage, cursor, heat, threshold, and mode. No background
  cooling, busy waiting, partial writes, or arbitrary delays are permitted.""",
    },
    {
        "name": "VerifiableMailbox",
        "source": "prompts_from_other_benchmarks/data.json#ClassEval_211",
        "contract": """Contract:
- Construct with positive byte capacity. Provide receive(byte[] content,
  boolean draft), fetchIfMatch(long messageId, String validator),
  prune(long throughId), and snapshotHeader(). Start with an empty FIFO inbox.
  Reject null/empty content and content larger than total capacity before
  eviction. Copy content before retaining or returning it.
- receive atomically checks for byte-for-byte duplicate content across all
  currently stored messages, regardless of draft status. A duplicate returns
  -1 without eviction or other mutation. Do not assume hashes are unique.
- Otherwise evict oldest messages until the new content fits, assign a unique
  positive increasing ID, and insert at the tail. Occupancy is the sum of
  stored payload lengths. Return the assigned ID. Reject ID exhaustion before
  evicting anything; IDs are never reused after eviction or pruning.
- Each message's validator is "W-" + ID for drafts and "S-" + ID otherwise.
  Maintain weak and strong validator sets that correspond exactly to the
  current draft and nondraft inbox contents. Duplicate checking, eviction,
  insertion, accounting, and both validator sets change as one transition.
- fetchIfMatch returns a detached message only when both ID and exact
  validator match a currently stored entry, otherwise null. Fetch does not
  consume the message. Reject nonpositive IDs and null validators.
- prune requires a nonnegative throughId and atomically removes all messages
  with ID <= throughId, reclaiming bytes and removing their validators.
  Return the number removed. Evicted/pruned content may be received again
  with a new ID and validator.
- snapshotHeader coherently returns capacity, occupancy, ordered message IDs,
  and copied strong/weak validator arrays from one observation. No snapshot
  may contain a validator for an absent message or omit one for a present
  message. Operations never wait for capacity or invoke external services.""",
    },
    {
        "name": "PersistedScheduleContext",
        "source": "prompts_from_other_benchmarks/data.json#ClassEval_260",
        "contract": """Contract:
- Define an injected ScheduleSink with void persist(ScheduleSnapshot snapshot).
  Provide reserve(long start, long end), cancel(long reservationId),
  flush(), and snapshot(). Start empty at committed and durable revision 0;
  no background thread or constructor callback is permitted.
- Reservations are half-open intervals [start, end), start < end, and cannot
  overlap. reserve atomically checks conflicts and inserts a unique positive
  ID, returning -1 on conflict. cancel removes an existing ID and returns
  whether it existed. Each successful mutation advances committed revision
  once; failures do not. Reject invalid inputs and counter overflow before
  mutation. These operations commit in memory, not synchronously to the sink.
- flush captures its target committed revision at entry and returns only
  when durable revision >= target, or throws a retained persistence failure.
  A caller may claim the single flusher role; other callers wait interruptibly
  while releasing the state monitor. The flusher copies a current schedule
  and its revision under the monitor and calls persist outside it.
- Sink calls are serialized in nondecreasing revision order. Successful
  completion advances durable revision to that snapshot's revision and wakes
  waiters; later commits are not accidentally marked durable. Intermediate
  revisions may be coalesced into a newer complete snapshot. Never let an
  older sink call overwrite a newer durable state.
- A sink RuntimeException is terminal for persistence: retain it, clear the
  flusher claim, and wake all waiters. flush succeeds if its target is already
  durable, otherwise propagates that failure. Subsequent reserve/cancel calls
  throw IllegalStateException with the retained cause; existing in-memory
  commits are not rolled back. Interrupted waiters cannot strand the flusher.
- snapshot coherently reports copied reservations, committed/durable revisions,
  flusher status, and failure. Give the sink its own detached snapshot so it
  cannot mutate state. Slow persistence must not block snapshot or in-memory
  commits before failure is published. The sink may reenter snapshot but must
  not recursively flush. All exits release the flusher role and notify waiters.""",
    },
    {
        "name": "LexicalGraphProvider",
        "source": "prompts_from_other_benchmarks/data.json#ClassEval_66",
        "contract": """Contract:
- Provide replaceVocabulary(String[] words), setContext(String sentence),
  openSession(), and snapshot(). Define a Session type with findMatches(String
  prefix), dependencies(int tokenIndex), and snapshot(). Start with empty
  vocabulary and context, vocabulary revision 0, and context generation 0.
- Vocabulary entries are nonempty ASCII letter strings, normalized to lowercase
  and deduplicated. Reject null arrays, entries, or sentences and invalid words
  without mutation. Copy inputs. Context tokenization extracts maximal ASCII
  letter runs and lowercases them; all other characters are separators.
  Preserve repeated tokens and their ordered zero-based indexes.
- replaceVocabulary prepares a complete vocabulary outside the state monitor,
  then atomically publishes it and advances vocabulary revision. setContext
  likewise prepares tokens and an index of all occurrences outside the monitor,
  then publishes them and advances context generation. Concurrent replacements
  take effect in commit order, not invocation order; a session pins the pair
  of vocabulary/context versions current at its own capture.
- openSession atomically captures one complete vocabulary, token sequence,
  occurrence index, and both versions. Existing sessions never change when
  either provider component is replaced. Reject counter overflow before
  publication; no partially built vocabulary or index may become visible.
- Session.findMatches accepts an ASCII-letter prefix, including empty, and
  returns distinct vocabulary words with that prefix which occur in the pinned
  context, sorted by decreasing length then ascending lexical order. Matching
  is case-insensitive through the same lowercase normalization.
- Session.dependencies requires a valid token index and returns the increasing
  indexes of all later pinned-context tokens which belong to the pinned
  vocabulary. Repeated words retain distinct occurrence indexes. Invalid
  prefixes or indexes throw IllegalArgumentException.
- Provider and session snapshots return detached vocabulary, tokens, occurrence
  indexes, and paired versions. Queries on one session must never mix new
  vocabulary with old context or expose retained arrays. Expensive preparation
  and session queries must not hold the provider's state monitor.""",
    },
    {
        "name": "Warehouse",
        "source": "prompts_from_other_benchmarks/ClassEval_data.json#ClassEval_95",
        "contract": """Contract:
- Construct with a copied nonempty long[] of nonnegative stock quantities;
  products are array indexes. Provide reserveOrder(long orderId, long[]
  quantities), shipOrder(long orderId), cancelOrder(long orderId),
  restock(int product, long quantity), and snapshot().
- Order IDs are positive and single-use, including unsuccessful reservations.
  Bundles contain one nonnegative quantity per product and at least one
  positive quantity. Reject invalid inputs and duplicate IDs with
  IllegalArgumentException before mutation; copy bundles before retention.
- reserveOrder atomically registers the ID, checks every product's available
  stock, and reserves the whole bundle or none. Return true and record RESERVED
  on success; otherwise record REJECTED and return false without changing
  stock. This operation never blocks waiting for inventory.
- Keep available and reserved quantities separately. A reservation transfers
  quantities from available to reserved. shipOrder atomically changes RESERVED
  to SHIPPED and consumes its reserved quantities exactly once. cancelOrder
  atomically changes RESERVED to CANCELLED and returns its entire reservation
  to available stock exactly once. Both return false for unknown or already
  terminal IDs, and reject nonpositive IDs.
- restock requires a valid product and positive quantity. Add only to available
  stock, checking available + reserved + addition for long overflow before
  mutation, so any future cancellation remains representable.
- Shipping and cancellation racing for one order cannot both succeed.
  A late shipment must not revive a cancelled order, and rollback must preserve
  other reservations, shipments, and intervening restocks.
- snapshot returns coherent copied available/reserved arrays and detached
  ordered order records containing IDs, bundles, and statuses. No caller-owned
  or returned array may mutate stock or an order. Use one synchronization
  protocol across inventory and order state; no external callbacks are needed.""",
    },
    {
        "name": "RPGCharacter",
        "source": "prompts_from_other_benchmarks/ClassEval_data.json#ClassEval_73",
        "contract": """Contract:
- Construct with nonempty name, health in 1..100, and nonnegative int attack
  power and defense. Start at level 1 and experience 0; maximum health is 100.
  Provide attack(RPGCharacter other), heal(), gainExperience(long amount),
  and snapshot(). Reject null names/targets and self-attacks.
- An attack is one atomic two-character encounter: if either character is
  dead, return a detached no-op result. Otherwise read both current attribute
  sets, compute max(0, attackerPower - targetDefense), and reduce target health
  no lower than zero. The first transition from positive health to zero awards
  the attacker exactly 10 experience as part of the same encounter.
- Experience is long-valued. Every 100 accumulated points increases level
  by one and consumes 100 points, retaining a remainder in 0..99. Each level
  adds 2 attack power and 1 defense; leveling does not change health.
  gainExperience accepts nonnegative amounts for a living character; a dead
  character gets IllegalStateException. Check all arithmetic and level/stat
  overflow before any mutation, including a killing blow's reward, so failure
  cannot leave damage committed without its reward.
- heal atomically restores up to 10 health, capped at 100, and returns the
  resulting health. A dead character remains at zero and cannot be resurrected.
  Death is terminal; repeated attacks cannot grant duplicate death rewards.
- Use a deadlock-free protocol for encounters, such as immutable globally
  unique character IDs and consistent two-monitor ordering. Allocate IDs
  safely without wrapping; reciprocal attacks must terminate without
  lock-order inversion. All single-character operations must participate
  in the same synchronization protocol as encounters.
- Return detached encounter results describing applied damage, whether this
  encounter caused death, and both characters' post-encounter snapshots,
  captured together. snapshot coherently reports name, ID, health, attack,
  defense, level, experience, and alive status without exposing mutable state.
  No callbacks, background threads, randomness, or real-time delays are needed.""",
    },
]

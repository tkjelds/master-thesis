# Pipeline problem breakdown

Inventory reviewed on 2026-10-24 against the current prompt definitions.

## Overview

| Category | Problems | Purpose | Definition file |
| --- | ---: | --- | --- |
| `datastructures` | 18 | Familiar concurrent collection abstractions and common representations. | [datastructures.py](../pipeline/prompt_generator/datastructures.py) |
| `novel_problems` | 20 | Application-style concurrency problems with explicit state transitions and failure rules. | [novel_problems.py](../pipeline/prompt_generator/novel_problems.py) |
| `advanced_datastructures` | 10 | Specialized algorithms, structural invariants, hybrid representations, and persistence. | [advanced_datastructures.py](../pipeline/prompt_generator/advanced_datastructures.py) |
| **Total** | **48** | | |

Categories are assembled by [problems.py](../pipeline/prompt_generator/problems.py).
Every generated prompt uses the same [shared requirements](../pipeline/prompt_generator/base_prompt.py):
self-contained Java, no imports or library collection/concurrency implementations,
and built-in synchronization primitives. Synchronized blocks are allowed;
synchronized method declarations, busy waiting, and arbitrary delays are not.
The library examples below identify familiar problem types, not dependencies
that generated implementations may use.

The classification distinguishes **mainstream collection APIs** from
**specialized implementation contracts**, not simply easy from difficult.
For example, skip lists stay regular because Java exposes concurrent skip-list
collections, while explicit red-black-tree invariants belong in advanced.

## 1. Regular data structures

These entries currently receive a generic request to implement a thread-safe
concurrent version of the named structure. They do **not** yet have individual
API contracts. The descriptions and challenges below explain their intended
roles; they should not be read as requirements already enforced by the prompts.

| Problem | Intended role and mainstream analogue | Main concurrency concerns |
| --- | --- | --- |
| `map` | General key-value collection; Java `ConcurrentHashMap`. | Atomic replacement/removal, visibility, resizing, and iteration policy. |
| `skip_list_map` | Ordered map; Java `ConcurrentSkipListMap`. | Maintaining consistent multi-level links during insertion and deletion. |
| `skip_list_set` | Ordered unique-element collection; Java `ConcurrentSkipListSet`. | Duplicate prevention and consistent ordered traversal during updates. |
| `set` | Unordered unique-element collection; Java concurrent-map key sets or `CopyOnWriteArraySet`. | Atomic membership changes and duplicate prevention. |
| `list` | Ordered indexed collection; Java `CopyOnWriteArrayList` is one concurrent strategy. | Positional changes, resizing, and consistency of traversal or snapshots. |
| `queue` | General FIFO collection; Java `ConcurrentLinkedQueue` or .NET `ConcurrentQueue`. | Exactly-once removal, FIFO ordering, and an explicit empty-queue policy. |
| `deque` | Double-ended collection; Java `ConcurrentLinkedDeque`. | Coordinating both endpoints, especially transitions through empty and singleton states. |
| `linked_queue` | Linked-node FIFO representation; Java concurrent or blocking linked queues. | Publishing initialized nodes and maintaining head/tail consistency. |
| `linked_deque` | Linked-node double-ended representation; Java `ConcurrentLinkedDeque` or `LinkedBlockingDeque`. | Preserving links in both directions during competing endpoint operations. |
| `array_queue` | Array-backed queue; Java `ArrayBlockingQueue` is a bounded example. | Wraparound, occupancy accounting, slot reuse, and full/empty policies. |
| `priority_queue` | Priority-ordered removal; Java `PriorityBlockingQueue`. | Atomic extremum selection and repair of heap ordering during updates. |
| `delay_queue` | Time-gated removal; Java `DelayQueue`. | Preventing early delivery and waking waiters when an earlier deadline arrives. |
| `stack` | LIFO collection; .NET `ConcurrentStack`, or Java concurrent-deque stack operations. | Atomic push/pop and correct empty/singleton transitions. |
| `collection` | General collection abstraction, not a specific representation. | Defining which operations and consistency guarantees the implementation actually supports. |
| `dictionary` | Key-value collection; .NET `ConcurrentDictionary`. | The same broad concerns as `map`; currently no contract distinguishes the two. |
| `bag` | Unordered collection permitting duplicates; .NET `ConcurrentBag`. | Retaining duplicate occurrences and removing each occurrence at most once. |
| `counter` | Shared numeric state; Java atomic counters and `LongAdder` illustrate different consistency choices. | Lost increments, overflow, and whether reads must be exact or approximate under contention. |
| `linked_list` | General linked-list representation rather than a direct Java concurrent-list class. | Link consistency, removal, traversal, and safe publication of nodes. |

### Interpretation and overlap

- `map` and `dictionary` are effectively synonyms until given different contracts.
- `queue` overlaps with `linked_queue` and `array_queue`; `deque` overlaps with
  `linked_deque`; `list` overlaps with `linked_list`. Representation-specific
  contracts could make these useful controlled comparisons.
- `collection` is especially underspecified: it names an abstraction rather
  than an algorithm or a clearly bounded operation set.
- `linked_list` is a general thread-safe baseline, not a claim that
  `java.util.concurrent` contains a directly equivalent indexed linked list.
- A bag permitting duplicate items does not necessarily expose per-value
  occurrence counts. Those semantics need an explicit contract.

Mainstream references:
[Java concurrent collections](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/package-summary.html)
and [.NET thread-safe collections](https://learn.microsoft.com/en-us/dotnet/standard/collections/thread-safe/).

## 2. Novel problems

These are Java concurrency adaptations of selected ClassEval-derived problems,
not claims that the original benchmark problems already had these concurrency
contracts. Each definition retains its source dataset and record ID.

| Problem | What it models | Main concurrency challenge |
| --- | --- | --- |
| `HierarchicalScheduler` | Reservations over a dynamically growing hierarchy, with conflicts between ancestors and descendants. | Atomic conflict checking across the relevant tree, unique child creation, and coherent subtree snapshots. |
| `ThermostaticBuildOrchestrator` | A bounded job queue with load-dependent concurrency limits and manual overrides. | Interruptible admission, hysteresis, cancellation, shutdown/draining, and reentrant callbacks outside monitors. |
| `ResolvingVendingMachine` | Multi-product purchases reserving stock and shared funds before a dispenser callback. | Exactly-once commit or rollback when cancellation races with callback completion; preserving unrelated updates. |
| `HarmonicBufferedStream` | Serialized chunk reading, accumulated buffer metadata, and one completion notification. | Coordinating read/close/EOF races, exactly-once completion, callback reentrancy, and failure propagation. |
| `UserSpaceProvisioner` | Versioned installation attempts with shared results and reinstall supersession. | Joining an in-flight attempt, preventing stale completion from overwriting a newer generation, and waking waiters on every outcome. |
| `VersionedSlotManager` | Interval reservations guarded by an expected revision. | Atomic version-check-and-mutate operations, conflict detection, and preventing revision reuse across state changes. |
| `TaggedCapacityBuffer` | Byte-bounded tagged messages with eviction, blocking insertion, matching reads, and cross-buffer transfers. | Capacity accounting, exactly-once delivery, deadlock-free two-buffer moves, and shutdown notifications. |
| `AdaptiveTrustVault` | Credential-checked payload storage whose TTL changes with retrieval metrics. | Making classification, metric updates, TTL recomputation, and delivery one coherent decision; coordinating cleanup. |
| `SecureMetricAggregator` | Expiring measurement batches and summary statistics. | Capturing one exact dataset under synchronization, then computing outside the monitor without exposing live arrays. |
| `TemporalForexCache` | Expiring currency rates with atomic batch updates and conversions. | Reading compatible rate pairs and one revision/time, avoiding mixed batches, and pruning without deleting replacements. |
| `BitmaskedTimelineStage` | Versioned outcome state and an ordered event-dispatch queue. | Keeping bitmask/message/label consistent and serializing callbacks without holding the state monitor. |
| `InventoryTransactionBuffer` | A single replaceable staging slot for inventory deltas, guarded by owner and generation. | Preventing stale commits/discards from affecting a newer staged operation and applying inventory changes atomically. |
| `ChatSessionStream` | User membership, structured messages, rendered history, and a shared read cursor. | Atomic updates to both histories, disjoint concurrent reads, and close-versus-send ordering. |
| `GomokuVersionState` | A versioned game board with moves, resets, turns, and terminal outcomes. | Atomically validating a move and publishing all derived metadata; preventing pre-reset revisions from authorizing later moves. |
| `ThermostaticCircuitStream` | An append-only byte stream whose reads/writes depend on heat and a mutable threshold. | Waiting on changing predicates, all-or-nothing writes, interruption, threshold changes, and draining after close. |
| `VerifiableMailbox` | Byte-bounded deduplicated messages with draft/strong validators. | Updating inbox contents, eviction, byte accounting, and validator sets as one transition. |
| `PersistedScheduleContext` | In-memory reservations plus explicitly flushed durable snapshots. | Serializing persistence outside the monitor, tracking committed versus durable revisions, and releasing waiters after failure. |
| `LexicalGraphProvider` | Replaceable vocabulary/context indexes and sessions pinned to captured versions. | Safe publication of prepared state and immutable sessions that never mix versions during later provider updates. |
| `Warehouse` | Multi-product reservations followed by shipment or cancellation. | All-or-nothing reservation, exactly one terminal order transition, and consistent available/reserved stock. |
| `RPGCharacter` | Two-character combat with health, experience, and level changes. | Deadlock-free two-object locking, atomic damage/reward updates, and preventing duplicate rewards for one death. |

The category's strongest contribution is **compound state and lifecycle
coordination**: generation tokens, transaction ownership, cancellation,
callbacks, shutdown, cross-object operations, and coherent snapshots. It is
not merely a set of renamed collections. External work is modeled through
injected interfaces rather than real services or filesystem operations.

## 3. Advanced data structures

These entries specify algorithms and invariants, rather than only naming an
abstract collection. The C5-derived entries are concurrent-Java adaptations
inspired by C5, not ports with identical APIs or proof that C5 itself is
thread-safe. See the separate [C5 overview](c5-data-structures.md).

| Problem | Required structure/features | Main concurrency challenge |
| --- | --- | --- |
| `MysteriousHashMap` | Striped hash map with per-stripe bucket arrays, immutable bucket lists, reads that acquire no lock, and per-stripe resizing. | Publishing buckets and replacement arrays safely while preserving writer coordination and read visibility. |
| `TreeSet` | Red-black-tree set with subtree counts, indexed access, rank, and O(1) persistent snapshot capture. | Preserving balancing/count invariants while sharing historical nodes that must never be mutated. |
| `TreeBag` | Red-black-tree multiset with multiplicities, occurrence-based indexing/rank, and persistent snapshots. | Atomically updating counts and subtree totals without changing prior snapshots. |
| `TreeDictionary` | Red-black-tree map with indexed entries, rank, immutable returned entries, and persistent snapshots. | Making value replacement and structural updates consistent while preserving historical mappings. |
| `IntervalHeap` | Array-backed double-ended priority queue with stable update/delete handles. | Repairing both heap orders and handle locations atomically; rejecting stale or foreign handles. |
| `HashedArrayList` | Duplicate-free array list plus a hash index mapping elements to positions. | Updating array contents, shifted indices, hash entries, and size as one operation. |
| `binary_search_tree` | Explicit unbalanced node-based search-tree set with deletion and sorted snapshots. | Safe pointer changes and root replacement, particularly deletion of two-child nodes. |
| `red_black_tree` | Explicit balanced search-tree set with insertion/deletion repair and copied sorted snapshots. | Hiding intermediate rotations/recoloring and maintaining all balancing invariants. |
| `b_tree` | Multi-key B-tree with configurable minimum degree, splitting, borrowing, merging, and root collapse. | Updating separator keys, node occupancy, child links, and root state consistently. |
| `HashedLinkedList` | Duplicate-free doubly linked list plus a hash table mapping elements directly to nodes. | Keeping hash membership, bidirectional links, endpoints, and removals consistent. |

### Interpretation and overlap

`red_black_tree` and `TreeSet` deliberately differ: the former is a basic
balanced-tree implementation contract; the latter additionally requires rank,
indexing, and structurally shared persistent snapshots. `TreeBag` and
`TreeDictionary` extend that harder family with multiplicity and mapping
semantics.

`binary_search_tree` is not intrinsically an advanced algorithm. It belongs
here under the current classification because it requests a specific tree
implementation rather than a mainstream concurrent collection API.

Most advanced contracts permit a single structure-wide monitor. Consequently,
they measure structural correctness under concurrent calls, but do not by
themselves require scalable fine-grained concurrency or nonblocking progress.
`MysteriousHashMap` is a notable exception because it explicitly requires
striping and reads that acquire no lock.

## 4. Verdict and potential inclusions

### Verdict

**The three-category split is useful and defensible, with the largest current
weakness being specification consistency rather than a shortage of problems.**
Regular structures cover familiar collection families, novel problems exercise
compound application state, and advanced structures target richer invariants.
The 48 entries should not, however, be interpreted as 48 equally distinct or
equally difficult concurrency challenges.

The highest-value improvement is to give the 18 regular entries explicit
contracts: public operations, duplicate/null policies, capacity, blocking and
interruption behavior, traversal consistency, and required representation where
relevant. This would distinguish aliases and make generated implementations
more comparable. Advanced problems should state whether a global monitor is
acceptable or whether a finer-grained design is part of the experiment.

This inventory describes intended coverage, not empirically established bug
detection, functional correctness, scalability, or benchmark difficulty.
Pipeline tests alone do not establish those properties for generated programs.

### Potential additions to `datastructures`

| Suggested problem | Mainstream reference | Distinct coverage and priority |
| --- | --- | --- |
| `synchronous_queue` | Java [SynchronousQueue](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/SynchronousQueue.html). | **High:** zero-capacity producer/consumer rendezvous, handoff ownership, interruption, and timeout-versus-delivery races. Unlike an ordinary queue, successful insertion requires a matching consumer. |
| `transfer_queue` | Java [LinkedTransferQueue](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/LinkedTransferQueue.html). | **High:** distinguish ordinary enqueueing from transfer that waits for consumption; define cancellation without lost or duplicate delivery. |
| `copy_on_write_array_list` | Java [CopyOnWriteArrayList](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/CopyOnWriteArrayList.html). | **High:** publish immutable replacement arrays and provide snapshot traversal while writers serialize. More specific and experimentally useful than an unconstrained `list`. |
| `copy_on_write_array_set` | Java [CopyOnWriteArraySet](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/CopyOnWriteArraySet.html). | **Lower:** atomic uniqueness checks combined with copy-on-write publication. Useful, but overlaps substantially with the proposed copy-on-write list. |

Before adding another ordinary bounded queue, make boundedness and blocking
explicit in `array_queue` or `linked_queue`; otherwise the new problem may
duplicate existing coverage. Similarly, an exact atomic counter and a striped
accumulator could be separate variants only if their read/reset semantics are
clearly distinguished.

### Potential additions to `advanced_datastructures`

These are proposed implementation contracts, not claims that ready-made
concurrent versions exist in standard libraries.

| Suggested problem | Distinct structure | Concurrency coverage and priority |
| --- | --- | --- |
| `CuckooHashMap` | Multiple candidate buckets with displacement chains and coordinated rehashing; [libcuckoo](https://github.com/efficient/libcuckoo) is a concurrent implementation reference. | **High:** readers racing with relocation, duplicate detection across candidate locations, and resizing without losing entries. Structurally different from the current striped immutable-bucket map. |
| `PersistentHashTrieMap` | Hash-array-mapped trie with collision nodes, path copying, and immutable snapshots; [Clojure's PersistentHashMap](https://github.com/clojure/clojure/blob/master/src/jvm/clojure/lang/PersistentHashMap.java) is a structural reference. | **High:** safe root publication and coordinated concurrent writes without changing historical versions. A persistent immutable map alone is not a mutable concurrent-map contract; specify the shared update protocol separately. |
| `BPlusTree` | Records in linked leaves, internal separator keys, and ordered range traversal. | **Medium:** leaf/internal splits, merges, and range-read consistency. Add only with clear differences from `b_tree`; a B-link variant could explicitly require node-level coordination and safe traversal during splits. |
| `MeldablePriorityQueue` | A pairing or binomial heap with update handles and a meld operation. | **Medium:** atomic cross-heap ownership transfer, deadlock-free locking, and handle validity after meld. This adds behavior not covered by `IntervalHeap`. |
| `IntervalTree` | A balanced tree augmented with subtree maximum endpoints for overlap queries. | **Medium:** keeping augmentation correct through rotations and deletion, and returning overlap results from a defined coherent observation. It would give the reservation-themed novel problems a complementary structural benchmark. |

**Recommended order:** tighten the current regular contracts first, then add
`synchronous_queue`, `copy_on_write_array_list`, and `transfer_queue`. For the
advanced category, prioritize `CuckooHashMap` and `PersistentHashTrieMap` before
adding more balanced-tree variants. This expands synchronization and
representation diversity rather than primarily increasing the problem count.

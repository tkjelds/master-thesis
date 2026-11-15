
advanced_datastructures = [
    {
        "name": "MysteriousHashMap",
        "description": """Implement MysteriousHashMap<K, V>, a concurrent hash map with lock
striping in Java. Use MysteriousHashMap as the public top-level class name.""",
        "contract": """Contract:
- A top-level array holds references to Stripe objects, one for each stripe.
  Each Stripe object owns its entire stripe's bucket array.  
- Each bucket is an immutable list of immutable key-value pairs.
- Use multiple bucket arrays, one per stripe, rather than a single bucket array
  whose alternating buckets belong to different stripes. This layout keeps a
  stripe's bucket entries next to each other and rarely next to other stripes'
  bucket entries. It is intended to reduce the risk of false sharing between
  updatable bucket-array elements and between the stripes' size fields.
- containsKey, get, and forEach must proceed without taking a lock, using the
  immutable bucket lists. The other operations must use locking.
- Support reallocating an individual stripe's bucket array when that stripe
  becomes too full.
- Provide the public operations listed below. Each method has the same meaning
  as the corresponding method in Java's ConcurrentHashMap. These signatures are
  an informal API contract; no IMap interface declaration or implements clause
  is required. 

Public operations:
boolean containsKey(K k);
V get(K k);
V put(K k, V v);
V remove(K k);
int size();
void forEach(BiConsumer<K,V> consumer);""",
    },
    {
        "name": "TreeSet",
        "description": """Implement TreeSet<T>, a concurrent indexed red-black-tree
set in Java, inspired by C5. Use TreeSet as the public top-level class name.""",
        "contract": """Contract:
- Construct with a nonnull Order<T> defining int compare(T left, T right).
  Define this interface in the same file. The order is deterministic, total,
  and does not mutate or reenter the collection. Reject null elements.
  Elements must not change their ordering while retained by the collection.
- Store one representative per comparison-equivalence class: compare(a, b)
  == 0 means the same set element. add returns false without replacement
  when an equivalent element already exists; remove returns whether one existed.
- Implement a red-black tree yourself. Preserve binary-search ordering,
  a black root, no red parent/red child pairs, and equal black height on
  every root-to-null path after both insertion and deletion. Maintain subtree
  element counts through rotations and rebalancing.
- get(index) returns the element at a zero-based sorted position; rank(item)
  returns the number of elements strictly smaller than item, even if absent.
  Invalid indices throw IndexOutOfBoundsException. Search, updates, indexed
  access, and rank take O(log n) time; do not flatten or rebuild the tree.
- snapshot returns a Snapshot<T> with int size(), T get(int index), and
  boolean contains(T item). It captures one coherent state and never changes
  after later writes. Use persistent node sharing with path copying, not a
  full-tree copy; snapshot creation takes O(1) time. Never modify nodes still
  reachable from a snapshot. Snapshots retain element references, not deep copies.
- All live operations are linearizable, including size and snapshot capture.
  A single collection-wide monitor is permitted. Invalid inputs throw before
  mutation; null arguments throw NullPointerException.

Public operations:
boolean add(T item);
boolean remove(T item);
boolean contains(T item);
int size();
T get(int index);
int rank(T item);
Snapshot<T> snapshot();""",
    },
    {
        "name": "TreeBag",
        "description": """Implement TreeBag<T>, a concurrent indexed red-black-tree
multiset in Java, inspired by C5. Use TreeBag as the public top-level class name.""",
        "contract": """Contract:
- Construct with a nonnull Order<T> defining int compare(T left, T right).
  Define this interface in the same file. The order is deterministic, total,
  and does not mutate or reenter the collection. Reject null elements.
  Elements must not change their ordering while retained by the collection.
- Store one red-black-tree node per comparison-equivalence class, with a
  positive occurrence count. add inserts one occurrence, retaining the existing
  representative for an equivalent item. remove deletes one occurrence and
  returns false if absent. Delete a node only when its count reaches zero.
- size counts all occurrences; distinctSize counts distinct classes. count
  returns an item's multiplicity, or zero if absent. Reject an addition with
  IllegalStateException before mutation if the total size would overflow int.
- Preserve binary-search ordering, a black root, no red parent/red child
  pairs, and equal black height on every root-to-null path after insertion
  and deletion. Maintain subtree occurrence totals through count changes,
  rotations, and rebalancing.
- get(index) selects a zero-based position in the sorted sequence including
  repetitions; rank(item) counts occurrences strictly smaller than item.
  Invalid indices throw IndexOutOfBoundsException. Search, updates, selection,
  and rank take O(log d) time for d distinct items; do not expand repetitions.
- snapshot returns a Snapshot<T> with int size(), int distinctSize(),
  int count(T item), and T get(int index). Capture a coherent immutable
  historical state in O(1) time using persistent node sharing and path copying.
  Subsequent count updates and node deletion must not alter earlier snapshots.
  Snapshots retain element references, not deep copies.
- All live operations are linearizable. A single collection-wide monitor is
  permitted. Invalid inputs throw before mutation; null arguments throw
  NullPointerException.

Public operations:
void add(T item);
boolean remove(T item);
int count(T item);
int size();
int distinctSize();
T get(int index);
int rank(T item);
Snapshot<T> snapshot();""",
    },
    {
        "name": "TreeDictionary",
        "description": """Implement TreeDictionary<K, V>, a concurrent sorted
red-black-tree map in Java, inspired by C5. Use TreeDictionary as the public
top-level class name.""",
        "contract": """Contract:
- Construct with a nonnull Order<K> defining int compare(K left, K right).
  Define this interface in the same file. The order is deterministic, total,
  and does not mutate or reenter the collection. Reject null keys and values.
  Keys must not change their ordering while retained by the collection.
- Comparison equality identifies a unique key. put inserts a mapping or
  replaces its value atomically, returning the previous value or null if
  absent. Retain the original key representative on replacement. get and
  remove return the associated value, or null if absent.
- Implement a red-black tree yourself. Preserve binary-search ordering,
  a black root, no red parent/red child pairs, and equal black height on
  every root-to-null path after insertion and deletion. Maintain subtree
  mapping counts through rotations and rebalancing.
- entryAt(index) returns an immutable Entry<K, V> with K key() and V value()
  at a zero-based sorted-key position. rank(key) counts keys strictly smaller
  than key, even if absent. Invalid indices throw IndexOutOfBoundsException.
  Search, updates, indexed access, and rank take O(log n) time.
- snapshot returns a Snapshot<K, V> with int size(), V get(K key), and
  Entry<K, V> entryAt(int index). Capture one coherent historical state in
  O(1) time using persistent node sharing and path copying, not full copying.
  Later replacement, insertion, or deletion must not alter a snapshot or a
  previously returned entry. Keys and values themselves are not deep-copied.
- All live operations are linearizable. A single collection-wide monitor is
  permitted. Define the snapshot and entry types in the same file. Invalid
  inputs throw before mutation; null arguments throw NullPointerException.

Public operations:
V put(K key, V value);
V get(K key);
V remove(K key);
boolean containsKey(K key);
int size();
Entry<K, V> entryAt(int index);
int rank(K key);
Snapshot<K, V> snapshot();""",
    },
    {
        "name": "IntervalHeap",
        "description": """Implement IntervalHeap<T>, a concurrent double-ended
priority queue with update handles in Java, inspired by C5. Use IntervalHeap
as the public top-level class name.""",
        "contract": """Contract:
- Construct with a nonnull Order<T> defining int compare(T left, T right).
  Define this interface in the same file. The order is deterministic, total,
  and does not mutate or reenter the collection. Reject null elements.
  Duplicate priorities and duplicate elements are permitted. Elements must not
  change their ordering except through replace.
- Implement an array-backed interval heap, not two independent heaps or a
  sorted list. Each node holds a low and high endpoint with low <= high.
  Low endpoints form a min-heap and high endpoints form a max-heap. A final
  singleton node acts as both endpoints. Grow storage as needed.
- add returns a distinct opaque Handle<T> identifying that insertion, even
  when equal items occur multiple times. Define the handle type in this file.
  It must not expose mutable internal state.
- findMin/findMax return an extremal element without removal. deleteMin and
  deleteMax remove and return one extremal element, invalidating its handle.
  Ties may be resolved arbitrarily. Empty extremum operations throw
  IllegalStateException immediately rather than blocking.
- delete(handle) removes that exact insertion and returns its item.
  replace(handle, item) returns its old item and repairs ordering in either
  direction while preserving the handle's identity. Keep handle locations
  correct through endpoint swaps, heap repair, and array growth.
- Removed handles are permanently invalid. Null arguments throw
  NullPointerException; foreign or invalidated handles throw
  IllegalArgumentException without mutation. Never let a stale handle target
  an entry later occupying the same array position.
- findMin, findMax, and size take O(1) time. Addition takes amortized O(log n)
  time; deletion and replacement take O(log n) time without linear handle scans.
- All operations are linearizable. A single collection-wide monitor is
  permitted; concurrent replacement and deletion of one handle must behave
  like a valid sequential ordering.

Public operations:
Handle<T> add(T item);
T findMin();
T findMax();
T deleteMin();
T deleteMax();
T delete(Handle<T> handle);
T replace(Handle<T> handle, T item);
int size();""",
    },
    {
        "name": "HashedArrayList",
        "description": """Implement HashedArrayList<T>, a concurrent ordered,
duplicate-free array list with a hash index in Java, inspired by C5.
Use HashedArrayList as the public top-level class name.""",
        "contract": """Contract:
- Use a resizable array for sequence order and a custom hash table mapping
  each retained item to its current array index. Implement both yourself.
  Equality and hashing use equals and hashCode; equal items have equal hashes.
  Items must keep stable equality and hashes while retained. Reject null items.
- add appends; insert inserts before a zero-based index in [0, size].
  Both return false without mutation if an equal item already exists, otherwise
  true. Insertion order is independent of hash-bucket order.
- get, set, and removeAt require indices in [0, size). Invalid indices throw
  IndexOutOfBoundsException. set replaces an item and returns the previous
  representative. An item equal to the one already at that index is allowed;
  equality with an item at any other index throws IllegalArgumentException.
- remove(item) removes the equal item if present and returns whether it existed.
  contains and indexOf use the hash index; indexOf returns -1 when absent.
  Resolve hash collisions using equality, not just hash-code identity.
- Update sequence contents, hash entries, shifted indices, and size as one
  atomic operation. Handle array growth and hash-table rehashing without
  exposing stale positions or partially moved elements to other threads.
- get takes O(1) time. contains and indexOf take expected O(1) time with
  well-distributed hashes. Positional insertion/removal and set may take O(n)
  time to maintain the index; do not replace hash lookup with a sequence scan.
- snapshot returns a detached Object[] containing the sequence in order from
  one coherent state. Mutating the returned array must not affect the list.
  Items themselves are not deep-copied. Views are not required.
- All operations are linearizable. A single collection-wide monitor is
  permitted. Invalid inputs throw before mutation; null arguments throw
  NullPointerException. Concurrent insertion of equal items has one winner.

Public operations:
boolean add(T item);
boolean insert(int index, T item);
T get(int index);
T set(int index, T item);
T removeAt(int index);
boolean remove(T item);
boolean contains(T item);
int indexOf(T item);
int size();
Object[] snapshot();""",
    },
    {
        "name": "binary_search_tree",
        "description": """Implement BinarySearchTree<T>, a concurrent node-based
binary search tree set in Java. Use BinarySearchTree as the public top-level
class name.""",
        "contract": """Contract:
- Construct with a nonnull Order<T> defining int compare(T left, T right).
  Define this interface in the same file. The order is deterministic, total,
  and does not mutate or reenter the collection. Reject null elements.
  Elements must keep stable ordering while retained.
- Each node holds one element and left/right child references. Every element
  in the left subtree compares smaller than the node, and every element in
  the right subtree compares larger. Do not substitute a hash table, sorted
  array, skip list, or self-balancing tree.
- Comparison equality identifies the same set element. add returns false
  without replacing an existing equivalent element. remove returns false
  when absent; otherwise remove exactly that element, preserving every other
  element. Handle deletion of leaves, one-child nodes, two-child nodes, and
  the root, including removal of the last element.
- first and last return the smallest and largest retained elements. On an
  empty tree they throw IllegalStateException immediately rather than blocking.
  size is the exact number of retained elements.
- Search, insertion, deletion, and extremum lookup take O(h) time for tree
  height h; no balancing or logarithmic-height guarantee is required.
  size takes O(1) time.
- snapshot returns a detached Object[] in sorted order from one coherent
  observation. Later writes or caller changes to the array must not change
  each other's structure. Elements themselves are not deep-copied.
- All operations are linearizable. A single tree-wide monitor is permitted.
  Null arguments throw NullPointerException before mutation.

Public operations:
boolean add(T item);
boolean remove(T item);
boolean contains(T item);
T first();
T last();
int size();
Object[] snapshot();""",
    },
    {
        "name": "red_black_tree",
        "description": """Implement RedBlackTree<T>, a concurrent red-black-tree
set in Java. Use RedBlackTree as the public top-level class name.""",
        "contract": """Contract:
- Construct with a nonnull Order<T> defining int compare(T left, T right).
  Define this interface in the same file. The order is deterministic, total,
  and does not mutate or reenter the collection. Reject null elements.
  Elements must keep stable ordering while retained.
- Implement a node-based red-black tree yourself. Preserve strict binary-search
  ordering, a black root, no red parent/red child pairs, and equal black
  height on every root-to-null path. Null leaves count as black.
- Comparison equality identifies the same set element. add returns false
  without replacement if an equivalent element exists. remove returns false
  if absent; otherwise delete exactly that element and restore all red-black
  invariants using rotations and recoloring, including after root deletion.
- Search, insertion, deletion, and extremum lookup take O(log n) time.
  Do not rebuild the whole tree or substitute another collection structure.
  size maintains the exact element count in O(1) time.
- first and last return the smallest and largest elements, throwing
  IllegalStateException immediately when empty rather than blocking.
- snapshot returns a detached Object[] in sorted order from one coherent
  observation in O(n) time. Elements themselves are not deep-copied. Unlike
  the indexed persistent TreeSet problem, rank operations, subtree counts,
  and structurally shared historical snapshots are not required.
- All operations are linearizable. Readers must not observe intermediate
  rotations or partially repaired deletion state. A single tree-wide monitor
  is permitted. Null arguments throw NullPointerException before mutation.

Public operations:
boolean add(T item);
boolean remove(T item);
boolean contains(T item);
T first();
T last();
int size();
Object[] snapshot();""",
    },
    {
        "name": "b_tree",
        "description": """Implement BTree<T>, a concurrent in-memory B-tree set
in Java. Use BTree as the public top-level class name.""",
        "contract": """Contract:
- Construct with int minimumDegree and a nonnull Order<T> defining
  int compare(T left, T right). Define this interface in the same file.
  Reject minimumDegree < 2 or a degree whose node-array capacities overflow
  int with IllegalArgumentException. The order is deterministic, total, and
  does not mutate or reenter the collection. Elements keep stable ordering
  while retained; null arguments throw NullPointerException.
- Implement a B-tree yourself using nodes with sorted key arrays and child
  arrays, not a binary tree, B+ tree, skip list, or flat sorted collection.
  With t = minimumDegree, each non-root node contains between t-1 and 2t-1
  keys. A nonempty root has between 1 and 2t-1 keys. An empty tree may use
  a null root or an empty leaf root.
- Each internal node with k keys has k+1 children whose key ranges are
  strictly separated by those keys. All leaves have the same depth.
  Comparison equality identifies a unique set element.
- add inserts one element and returns false without replacement when an
  equivalent element exists. Split full nodes and grow the root as needed.
- remove deletes one element and returns false if absent. Borrow from siblings
  or merge nodes as needed to preserve occupancy and equal leaf depth.
  Collapse an empty internal root to its remaining child. Do not lose or
  duplicate separator keys when splitting, borrowing, or merging.
- contains, add, remove, first, and last take O(log n) time for fixed t.
  Do not rebuild the full tree after an update. size takes O(1) time.
  Empty first/last calls throw IllegalStateException immediately.
- snapshot returns a detached Object[] in sorted order from one coherent
  observation in O(n) time. Elements themselves are not deep-copied.
- All operations are linearizable, including size and snapshot capture.
  A single tree-wide monitor is permitted. Intermediate splits, merges,
  separator changes, and root replacements must not be visible to readers.

Public operations:
boolean add(T item);
boolean remove(T item);
boolean contains(T item);
T first();
T last();
int size();
Object[] snapshot();""",
    },
    {
        "name": "HashedLinkedList",
        "description": """Implement HashedLinkedList<T>, a concurrent ordered,
duplicate-free doubly linked list with a hash index in Java, inspired by C5.
Use HashedLinkedList as the public top-level class name.""",
        "contract": """Contract:
- Use a doubly linked list for sequence order and a custom hash table mapping
  each retained item directly to its list node. Implement both yourself.
  Equality and hashing use equals and hashCode; equal items have equal hashes.
  Items must keep stable equality and hashes while retained. Reject null items.
- add appends; addFirst prepends; insert inserts before a zero-based index in
  [0, size]. Each returns false without mutation if an equal item already
  exists, otherwise true. List order is independent of hash-bucket order.
- get, set, and removeAt require indices in [0, size). Invalid indices throw
  IndexOutOfBoundsException. set replaces an item and returns the previous
  representative. An item equal to the one already at that position is allowed;
  equality with an item at another position throws IllegalArgumentException.
- remove(item) locates the equal item's node through the hash table, unlinks
  it, and returns true, or returns false if absent. contains uses hash lookup.
  Resolve hash collisions using equality, not just hash-code identity.
- removeFirst/removeLast remove and return the respective endpoint. On an
  empty list they throw IllegalStateException immediately rather than blocking.
- Maintain forward/backward links, endpoint references, hash entries, and
  size atomically. Removed nodes must not remain reachable through the hash
  table. Hash-table growth must preserve the identity of retained list nodes.
- Membership and removal by value take expected O(1) time with well-distributed
  hashes; end insertion takes amortized expected O(1) time. Endpoint removal
  takes expected O(1) time. Positional operations may traverse the list in O(n)
  time. Do not scan the list to implement membership or removal by value.
- snapshot returns a detached Object[] containing the sequence in order from
  one coherent state. Mutating the returned array must not affect the list.
  Items themselves are not deep-copied. Views are not required.
- All operations are linearizable. A single collection-wide monitor is
  permitted. Invalid inputs throw before mutation; null arguments throw
  NullPointerException. Concurrent insertion of equal items has one winner.

Public operations:
boolean add(T item);
boolean addFirst(T item);
boolean insert(int index, T item);
T get(int index);
T set(int index, T item);
T removeAt(int index);
boolean remove(T item);
T removeFirst();
T removeLast();
boolean contains(T item);
int size();
Object[] snapshot();""",
    },
]

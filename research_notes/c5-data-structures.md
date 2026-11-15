# C5 collection library: data-structure overview

[C5](https://github.com/sestoft/C5) is a generic collection library for C# and
other .NET languages, originally developed by Niels Kokholm and Peter Sestoft.
The current [license](https://github.com/sestoft/C5/blob/master/LICENSE) also
credits Rasmus Lystrøm.

**Concurrency clarification:** C5 is not a library of inherently thread-safe
concurrent collections. Shared mutable collections require caller-managed
synchronization. Its [locking example](https://github.com/sestoft/C5/blob/master/C5.UserGuideExamples/Locking.cs)
illustrates both races and explicit locking, including locking an entire
multi-step operation rather than just individual method calls.

## Main collection implementations

Links below point to implementation files in the upstream repository.

| Data structure | Implementation and purpose |
| --- | --- |
| [ArrayList](https://github.com/sestoft/C5/blob/master/C5/Arrays/ArrayList.cs) | Resizable array-backed list with indexed access; also supports stack and queue operations, although front removal makes queue use inefficient. |
| [LinkedList](https://github.com/sestoft/C5/blob/master/C5/LinkedLists/LinkedList.cs) | Doubly linked list supporting list, stack, and queue operations. |
| [HashedArrayList](https://github.com/sestoft/C5/blob/master/C5/Arrays/HashedArrayList.cs) | Array-backed list augmented with a hash index, combining positional ordering with fast membership lookup; disallows duplicates. |
| [HashedLinkedList](https://github.com/sestoft/C5/blob/master/C5/LinkedLists/HashedLinkedList.cs) | Doubly linked list augmented with a hash dictionary mapping items to nodes; disallows duplicates. |
| [CircularQueue](https://github.com/sestoft/C5/blob/master/C5/Arrays/CircularQueue.cs) | Growable circular-array collection supporting FIFO queue and LIFO stack operations. |
| [HashSet](https://github.com/sestoft/C5/blob/master/C5/Hashing/HashSet.cs) | Hash-based collection of distinct elements, without sorted order. |
| [HashBag](https://github.com/sestoft/C5/blob/master/C5/Hashing/HashBag.cs) | Hash-based multiset storing each distinct item together with its occurrence count. |
| [HashDictionary](https://github.com/sestoft/C5/blob/master/C5/Hashing/HashDictionary.cs) | Hash-based key-value map with unique keys. |
| [TreeSet](https://github.com/sestoft/C5/blob/master/C5/Trees/TreeSet.cs) | Red-black-tree set supporting sorted traversal, indexing, range operations, and read-only snapshots. |
| [TreeBag](https://github.com/sestoft/C5/blob/master/C5/Trees/TreeBag.cs) | Red-black-tree multiset with occurrence counts, sorted/indexed access, and read-only snapshots. |
| [TreeDictionary](https://github.com/sestoft/C5/blob/master/C5/Trees/TreeDictionary.cs) | Sorted key-value map backed by a red-black-tree set; also exposes snapshots. |
| [SortedArray](https://github.com/sestoft/C5/blob/master/C5/Arrays/SortedArray.cs) | Sorted dynamic-array set using binary search; useful for indexed, ordered access, with element shifting on updates. |
| [IntervalHeap](https://github.com/sestoft/C5/blob/master/C5/Heaps/IntervalHeap.cs) | Double-ended priority queue supporting access/removal at both minimum and maximum, plus handles for updating or deleting particular entries. |
| [WrappedArray](https://github.com/sestoft/C5/blob/master/C5/WrappedArray.cs) | Fixed-size list interface over an existing array; permits element replacement but not resizing. |

## Notable features and relevance to concurrency

- **Updatable list views:** sublists share storage with their underlying list,
  rather than copying elements. See the [view examples](https://github.com/sestoft/C5/blob/master/C5.UserGuideExamples/ViewPatterns.cs).
- **Persistent tree snapshots:** retain a read-only historical collection state.
  The [snapshot interface](https://github.com/sestoft/C5/blob/master/C5/Interfaces/IPersistentSorted.cs)
  does not itself establish a thread-safety guarantee.
- **Guarded wrappers:** expose read-only access, not synchronization; for example,
  [GuardedCollection](https://github.com/sestoft/C5/blob/master/C5/Wrappers/GuardedCollection.cs).
  They do not prevent changes through other references to the underlying collection.

For concurrent-data-structure research, C5 provides useful sequential designs and
operation specifications, but should not be treated as a ready-made concurrent
implementation baseline.

Further links: [project README](https://github.com/sestoft/C5#readme),
[usage examples](https://github.com/sestoft/C5/tree/master/C5.UserGuideExamples),
and [NuGet package](https://www.nuget.org/packages/C5/).

Reviewed against upstream `master` on 2026-10-24.

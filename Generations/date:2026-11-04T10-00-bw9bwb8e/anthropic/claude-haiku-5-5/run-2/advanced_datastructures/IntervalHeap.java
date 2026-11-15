interface Order<T> {
    int compare(T left, T right);
}

@Handle.ThreadSafe
final class Handle<T> {
    @interface ThreadSafe {}

    private final IntervalHeap<T> owner;
    private final IntervalHeap.Item<T> item;

    Handle(IntervalHeap<T> owner, IntervalHeap.Item<T> item) {
        this.owner = owner;
        this.item = item;
    }

    IntervalHeap<T> owner() {
        return owner;
    }

    IntervalHeap.Item<T> item() {
        return item;
    }
}

/**
 * Concurrent array-backed interval heap (double-ended priority queue) with update handles.
 *
 * Layout: flat array a[0..size). Node i occupies slots 2i (low endpoint) and 2i+1 (high endpoint).
 * A node with only slot 2i (the last node when size is odd) is a singleton acting as both endpoints.
 * Invariants: low <= high in every full node; low endpoints form a min-heap; high endpoints form
 * a max-heap; equivalently each child interval is contained in its parent interval.
 *
 * Concurrency policy: every operation runs under one collection-wide monitor. The collection is
 * unbounded, so add never blocks. Extremum operations on an empty collection throw
 * IllegalStateException immediately instead of waiting, so no wait/notify is required.
 */
@IntervalHeap.ThreadSafe
public class IntervalHeap<T> {
    @interface ThreadSafe {}


    private static final int INITIAL_CAPACITY = 16;

    /** Internal entry; identity-bearing and never reused once removed. */
    static final class Item<E> {
        E value;
        int pos = -1; // slot index while in heap, -1 once removed

        Item(E value) {
            this.value = value;
        }
    }

    private final Order<T> order;
    private final Object lock = new Object();
    private Item<T>[] heap;
    private int size;

    @SuppressWarnings("unchecked")
    public IntervalHeap(Order<T> order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        this.order = order;
        this.heap = (Item<T>[]) new Item[INITIAL_CAPACITY];
    }

    public Handle<T> add(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            ensureCapacity(size + 1);
            Item<T> it = new Item<>(item);
            int k = size;
            place(k, it);
            size = k + 1;
            repairAt(k);
            return new Handle<>(this, it);
        }
    }

    public T findMin() {
        synchronized (lock) {
            if (size == 0) {
                throw new IllegalStateException("heap is empty");
            }
            return heap[0].value;
        }
    }

    public T findMax() {
        synchronized (lock) {
            if (size == 0) {
                throw new IllegalStateException("heap is empty");
            }
            return heap[size == 1 ? 0 : 1].value;
        }
    }

    public T deleteMin() {
        synchronized (lock) {
            if (size == 0) {
                throw new IllegalStateException("heap is empty");
            }
            return removeAt(0).value;
        }
    }

    public T deleteMax() {
        synchronized (lock) {
            if (size == 0) {
                throw new IllegalStateException("heap is empty");
            }
            return removeAt(size == 1 ? 0 : 1).value;
        }
    }

    public T delete(Handle<T> handle) {
        if (handle == null) {
            throw new NullPointerException("handle");
        }
        synchronized (lock) {
            Item<T> it = resolve(handle);
            T value = it.value;
            removeAt(it.pos);
            return value;
        }
    }

    public T replace(Handle<T> handle, T item) {
        if (handle == null) {
            throw new NullPointerException("handle");
        }
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            Item<T> it = resolve(handle);
            T old = it.value;
            it.value = item;
            repairAt(it.pos);
            return old;
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    // ----- helpers; all called while holding lock -----

    private Item<T> resolve(Handle<T> handle) {
        if (handle.owner() != this) {
            throw new IllegalArgumentException("handle belongs to a different heap");
        }
        Item<T> it = handle.item();
        int p = it.pos;
        if (p < 0 || p >= size || heap[p] != it) {
            throw new IllegalArgumentException("handle is no longer valid");
        }
        return it;
    }

    @SuppressWarnings("unchecked")
    private void ensureCapacity(int needed) {
        if (needed <= heap.length) {
            return;
        }
        int cap = heap.length;
        while (cap < needed) {
            cap = cap * 2;
        }
        Item<T>[] grown = (Item<T>[]) new Item[cap];
        for (int i = 0; i < size; i++) {
            grown[i] = heap[i];
        }
        heap = grown;
    }

    private int cmp(Item<T> a, Item<T> b) {
        return order.compare(a.value, b.value);
    }

    private void place(int k, Item<T> it) {
        heap[k] = it;
        it.pos = k;
    }

    private void swap(int a, int b) {
        Item<T> t = heap[a];
        place(a, heap[b]);
        place(b, t);
    }

    /** Slot holding the high endpoint of node c (its low slot if c is a singleton). */
    private int highSlot(int c) {
        return (2 * c + 1 < size) ? 2 * c + 1 : 2 * c;
    }

    /** Removes the entry at slot k, moving the last slot into the hole and repairing. */
    private Item<T> removeAt(int k) {
        Item<T> removed = heap[k];
        int last = size - 1;
        Item<T> tail = heap[last];
        heap[last] = null;
        size = last;
        if (k != last) {
            place(k, tail);
            repairAt(k);
        }
        removed.pos = -1;
        return removed;
    }

    /** Restores invariants after the item at slot k changed (or was newly placed). */
    private void repairAt(int k) {
        if ((k & 1) == 0) {
            repairLow(k >>> 1);
        } else {
            repairHigh(k >>> 1);
        }
    }

    private void repairLow(int j) {
        int k = 2 * j;
        boolean full = k + 1 < size;
        if (full && cmp(heap[k], heap[k + 1]) > 0) {
            // New low exceeds high: swap so the node stays ordered.
            swap(k, k + 1);
            Item<T> lowItem = heap[k];
            siftUpMax(k + 1);
            siftDownMin(j, lowItem);
            return;
        }
        if (j > 0) {
            int pl = 2 * ((j - 1) >>> 1);
            if (cmp(heap[k], heap[pl]) < 0 || (!full && cmp(heap[k], heap[pl + 1]) > 0)) {
                siftUpMin(k);
                return;
            }
        }
        siftDownMin(j, heap[k]);
    }

    private void repairHigh(int j) {
        int lo = 2 * j;
        int k = lo + 1;
        if (cmp(heap[k], heap[lo]) < 0) {
            // New high is below low: swap so the node stays ordered.
            swap(lo, k);
            Item<T> highItem = heap[k];
            siftUpMin(lo);
            siftDownMax(j, highItem);
            return;
        }
        if (j > 0) {
            int ph = 2 * ((j - 1) >>> 1) + 1;
            if (cmp(heap[k], heap[ph]) > 0) {
                siftUpMax(k);
                return;
            }
        }
        siftDownMax(j, heap[k]);
    }

    /** Bubbles the low-slot item at k up the min chain. */
    private void siftUpMin(int k) {
        while (true) {
            int i = k >>> 1;
            if (i == 0) {
                return;
            }
            int p = (i - 1) >>> 1;
            int pl = 2 * p;
            if (cmp(heap[k], heap[pl]) < 0) {
                swap(k, pl);
                k = pl;
                continue;
            }
            int ph = pl + 1;
            if (cmp(heap[k], heap[ph]) > 0) {
                swap(k, ph);
                siftUpMax(ph);
            }
            return;
        }
    }

    /** Bubbles the high-slot item at k up the max chain. */
    private void siftUpMax(int k) {
        while (true) {
            int i = k >>> 1;
            if (i == 0) {
                return;
            }
            int p = (i - 1) >>> 1;
            int ph = 2 * p + 1;
            if (cmp(heap[k], heap[ph]) > 0) {
                swap(k, ph);
                k = ph;
                continue;
            }
            int pl = 2 * p;
            if (cmp(heap[k], heap[pl]) < 0) {
                swap(k, pl);
                siftUpMin(pl);
            }
            return;
        }
    }

    /** Places v as the low endpoint of node i, sifting it down the min chain. */
    private void siftDownMin(int i, Item<T> v) {
        while (true) {
            int c = -1;
            int c1 = 2 * i + 1;
            int c2 = c1 + 1;
            if (2 * c1 < size) {
                c = c1;
            }
            if (2 * c2 < size && (c < 0 || cmp(heap[2 * c2], heap[2 * c]) < 0)) {
                c = c2;
            }
            if (c < 0 || cmp(v, heap[2 * c]) <= 0) {
                place(2 * i, v);
                return;
            }
            Item<T> lc = heap[2 * c];
            place(2 * i, lc);
            int hiSlot = 2 * c + 1;
            if (hiSlot < size && cmp(v, heap[hiSlot]) > 0) {
                Item<T> hc = heap[hiSlot];
                place(hiSlot, v);
                v = hc;
            }
            i = c;
        }
    }

    /** Places v as the high endpoint of node i, sifting it down the max chain. */
    private void siftDownMax(int i, Item<T> v) {
        while (true) {
            int c = -1;
            int c1 = 2 * i + 1;
            int c2 = c1 + 1;
            if (2 * c1 < size) {
                c = c1;
            }
            if (2 * c2 < size && (c < 0 || cmp(heap[highSlot(c2)], heap[highSlot(c)]) > 0)) {
                c = c2;
            }
            if (c < 0 || cmp(v, heap[highSlot(c)]) >= 0) {
                place(highSlot(i), v);
                return;
            }
            Item<T> hc = heap[highSlot(c)];
            place(2 * i + 1, hc);
            int loSlot = 2 * c;
            if (2 * c + 1 < size && cmp(v, heap[loSlot]) < 0) {
                Item<T> lc = heap[loSlot];
                place(loSlot, v);
                v = lc;
            }
            i = c;
        }
    }
}

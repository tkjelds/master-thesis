@IntervalHeap.ThreadSafe
public final class IntervalHeap<T> {
    @interface ThreadSafe {}


    /**
     * Total, deterministic ordering over elements. Must not mutate or reenter the heap.
     */
    public interface Order<T> {
        int compare(T left, T right);
    }

    /**
     * Opaque handle identifying one insertion. Exposes no state.
     */
    public static final class Handle<T> {
        private final Entry<T> entry;

        private Handle(Entry<T> entry) {
            this.entry = entry;
        }
    }

    /**
     * Internal per-insertion record. Handles reference entries (not array positions),
     * so a stale handle can never be redirected to another entry.
     */
    private static final class Entry<T> {
        final IntervalHeap<T> owner;
        T item;
        int pos; // slot index while live, -1 once removed permanently

        Entry(IntervalHeap<T> owner, T item) {
            this.owner = owner;
            this.item = item;
            this.pos = -1;
        }
    }

    private static final int INITIAL_CAPACITY = 16;

    // Slot layout: node c occupies slots 2c (low endpoint) and 2c+1 (high endpoint).
    // The last node may be a singleton holding only its low slot.
    // Lows form a min-heap by node, highs form a max-heap by node.
    // All fields below are guarded by lock.
    private final Object lock = new Object();
    private final Order<T> order;
    private Entry<T>[] slots;
    private int size;

    /**
     * Operations never block. Policy: extremum operations and handle operations
     * on an empty heap or on an invalid handle fail immediately with
     * IllegalStateException / IllegalArgumentException respectively; add and
     * replace never block because storage grows as needed.
     */
    public IntervalHeap(Order<T> order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        this.order = order;
        this.slots = newSlots(INITIAL_CAPACITY);
        this.size = 0;
    }

    @SuppressWarnings("unchecked")
    private static <E> Entry<E>[] newSlots(int capacity) {
        return (Entry<E>[]) new Entry[capacity];
    }

    public Handle<T> add(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            if (size == slots.length) {
                grow();
            }
            Entry<T> e = new Entry<>(this, item);
            int p = size;
            slots[p] = e;
            e.pos = p;
            size = p + 1;
            repairNode(p >> 1);
            return new Handle<>(e);
        }
    }

    public T findMin() {
        synchronized (lock) {
            if (size == 0) {
                throw new IllegalStateException("heap is empty");
            }
            return slots[0].item;
        }
    }

    public T findMax() {
        synchronized (lock) {
            if (size == 0) {
                throw new IllegalStateException("heap is empty");
            }
            return size == 1 ? slots[0].item : slots[1].item;
        }
    }

    public T deleteMin() {
        synchronized (lock) {
            if (size == 0) {
                throw new IllegalStateException("heap is empty");
            }
            T item = slots[0].item;
            removeAt(0);
            return item;
        }
    }

    public T deleteMax() {
        synchronized (lock) {
            if (size == 0) {
                throw new IllegalStateException("heap is empty");
            }
            int p = size == 1 ? 0 : 1;
            T item = slots[p].item;
            removeAt(p);
            return item;
        }
    }

    public T delete(Handle<T> handle) {
        if (handle == null) {
            throw new NullPointerException("handle");
        }
        Entry<T> e = handle.entry;
        synchronized (lock) {
            checkLive(e);
            T item = e.item;
            removeAt(e.pos);
            return item;
        }
    }

    public T replace(Handle<T> handle, T item) {
        if (handle == null) {
            throw new NullPointerException("handle");
        }
        if (item == null) {
            throw new NullPointerException("item");
        }
        Entry<T> e = handle.entry;
        synchronized (lock) {
            checkLive(e);
            T old = e.item;
            e.item = item;
            repairNode(e.pos >> 1);
            return old;
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    // ---------------------------------------------------------------------
    // Internal helpers. All must be called while holding lock.
    // ---------------------------------------------------------------------

    private void checkLive(Entry<T> e) {
        if (e.owner != this) {
            throw new IllegalArgumentException("handle belongs to another heap");
        }
        if (e.pos < 0) {
            throw new IllegalArgumentException("handle is no longer valid");
        }
    }

    private void grow() {
        int newCap = Math.max(INITIAL_CAPACITY, slots.length * 2);
        Entry<T>[] next = newSlots(newCap);
        System.arraycopy(slots, 0, next, 0, size);
        slots = next;
    }

    private boolean hasHi(int node) {
        return 2 * node + 1 < size;
    }

    private int keySlot(int node) {
        return hasHi(node) ? 2 * node + 1 : 2 * node;
    }

    private int cmp(int slotA, int slotB) {
        return order.compare(slots[slotA].item, slots[slotB].item);
    }

    private void swap(int a, int b) {
        Entry<T> t = slots[a];
        slots[a] = slots[b];
        slots[b] = t;
        slots[a].pos = a;
        slots[b].pos = b;
    }

    /** Removes the entry at slot p, filling the hole with the last slot's entry. */
    private void removeAt(int p) {
        Entry<T> removed = slots[p];
        int last = size - 1;
        size = last;
        if (p != last) {
            Entry<T> x = slots[last];
            slots[last] = null;
            slots[p] = x;
            x.pos = p;
            repairNode(p >> 1);
        } else {
            slots[last] = null;
        }
        removed.pos = -1;
    }

    /**
     * Restores the interval-heap invariants after the value(s) of node c changed.
     */
    private void repairNode(int c) {
        if (!hasHi(c)) {
            // Singleton node: only the low slot exists.
            bubbleUpMin(c);
            return;
        }
        if (cmp(2 * c, 2 * c + 1) > 0) {
            swap(2 * c, 2 * c + 1);
        }
        int parent = (c - 1) >> 1;
        if (c > 0 && cmp(2 * c, 2 * parent) < 0) {
            bubbleUpMin(c);
        } else {
            downMin(c);
        }
        if (c > 0 && cmp(2 * c + 1, 2 * parent + 1) > 0) {
            bubbleUpMax(c);
        } else {
            downMax(c);
        }
    }

    /** Sifts the low value of node c upward; c must not be the root's high side. */
    private void bubbleUpMin(int c) {
        while (c > 0) {
            int p = (c - 1) >> 1;
            if (cmp(2 * c, 2 * p) < 0) {
                swap(2 * c, 2 * p);
                c = p;
            } else if (hasHi(p) && cmp(2 * p + 1, 2 * c) < 0) {
                swap(2 * p + 1, 2 * c);
                bubbleUpMax(p);
                return;
            } else {
                return;
            }
        }
    }

    /** Sifts the high value of node c (which has a high slot) upward. */
    private void bubbleUpMax(int c) {
        while (c > 0) {
            int p = (c - 1) >> 1;
            if (cmp(2 * c + 1, 2 * p + 1) > 0) {
                swap(2 * c + 1, 2 * p + 1);
                c = p;
            } else if (cmp(2 * p, 2 * c + 1) > 0) {
                swap(2 * p, 2 * c + 1);
                bubbleUpMin(p);
                return;
            } else {
                return;
            }
        }
    }

    /** Sinks the low value of node c (which has a high slot) downward. */
    private void downMin(int c) {
        while (true) {
            int l = 2 * c + 1;
            int r = 2 * c + 2;
            int m = -1;
            if (2 * l < size) {
                m = l;
            }
            if (2 * r < size && (m < 0 || cmp(2 * r, 2 * m) < 0)) {
                m = r;
            }
            if (m < 0 || cmp(2 * m, 2 * c) >= 0) {
                return;
            }
            swap(2 * c, 2 * m);
            if (hasHi(m) && cmp(2 * m, 2 * m + 1) > 0) {
                swap(2 * m, 2 * m + 1);
            }
            c = m;
        }
    }

    /** Sinks the high value of node c (which has a high slot) downward. */
    private void downMax(int c) {
        while (true) {
            int l = 2 * c + 1;
            int r = 2 * c + 2;
            int best = -1;
            if (2 * l < size) {
                best = l;
            }
            if (2 * r < size && (best < 0 || cmp(keySlot(r), keySlot(best)) > 0)) {
                best = r;
            }
            if (best < 0 || cmp(keySlot(best), 2 * c + 1) <= 0) {
                return;
            }
            if (!hasHi(best)) {
                // Singleton child: its only value sits in the low slot.
                swap(2 * c + 1, 2 * best);
                return;
            }
            swap(2 * c + 1, 2 * best + 1);
            if (cmp(2 * best, 2 * best + 1) > 0) {
                swap(2 * best, 2 * best + 1);
            }
            c = best;
        }
    }
}

@IntervalHeap.ThreadSafe
public final class IntervalHeap<T> {
    @interface ThreadSafe {}


    /**
     * Deterministic, total ordering over elements. Implementations must not mutate
     * or reenter the heap.
     */
    public interface Order<T> {
        int compare(T left, T right);
    }

    /**
     * Opaque handle identifying one insertion. Holds no public state; it becomes
     * permanently invalid once its entry is removed from the heap.
     */
    public static final class Handle<T> {
        private final Node<T> node;

        private Handle(Node<T> node) {
            this.node = node;
        }
    }

    /** Internal entry. Its index is maintained on every move; -1 once removed. */
    private static final class Node<T> {
        final IntervalHeap<T> owner;
        T item;
        int index;

        Node(IntervalHeap<T> owner, T item) {
            this.owner = owner;
            this.item = item;
            this.index = -1;
        }
    }

    /*
     * Blocking policy: the heap is unbounded, so add never blocks. Extremum
     * operations on an empty heap throw IllegalStateException immediately rather
     * than waiting. Consequently no wait/notify is required. All state is guarded
     * by a single private monitor, which gives linearizability and safe publication.
     *
     * Layout: flat array where positions 2k and 2k+1 form node k (low, high).
     * A node with only position 2k (the last position, when size is odd) is a
     * singleton that acts as both endpoints. Lows form a min-heap by node tree,
     * highs form a max-heap by node tree.
     */

    private static final int INITIAL_CAPACITY = 16;

    private final Object monitor = new Object();
    private final Order<T> order;
    private Node<T>[] heap;
    private int size;

    public IntervalHeap(Order<T> order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        this.order = order;
        this.heap = newNodeArray(INITIAL_CAPACITY);
        this.size = 0;
    }

    public Handle<T> add(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (monitor) {
            ensureCapacity(size + 1);
            Node<T> node = new Node<>(this, item);
            put(size, node);
            size++;
            repair(size - 1);
            return new Handle<>(node);
        }
    }

    public T findMin() {
        synchronized (monitor) {
            if (size == 0) {
                throw new IllegalStateException("heap is empty");
            }
            return heap[0].item;
        }
    }

    public T findMax() {
        synchronized (monitor) {
            if (size == 0) {
                throw new IllegalStateException("heap is empty");
            }
            return heap[size == 1 ? 0 : 1].item;
        }
    }

    public T deleteMin() {
        synchronized (monitor) {
            if (size == 0) {
                throw new IllegalStateException("heap is empty");
            }
            return removeAt(0);
        }
    }

    public T deleteMax() {
        synchronized (monitor) {
            if (size == 0) {
                throw new IllegalStateException("heap is empty");
            }
            return removeAt(size == 1 ? 0 : 1);
        }
    }

    public T delete(Handle<T> handle) {
        synchronized (monitor) {
            Node<T> node = checkHandle(handle);
            return removeAt(node.index);
        }
    }

    public T replace(Handle<T> handle, T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (monitor) {
            Node<T> node = checkHandle(handle);
            T old = node.item;
            node.item = item;
            repair(node.index);
            return old;
        }
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    // ----- internal helpers (all called while holding monitor) -----

    @SuppressWarnings("unchecked")
    private static <T> Node<T>[] newNodeArray(int capacity) {
        return (Node<T>[]) new Node[capacity];
    }

    private Node<T> checkHandle(Handle<T> handle) {
        if (handle == null) {
            throw new NullPointerException("handle");
        }
        Node<T> node = handle.node;
        int idx = node.index;
        if (node.owner != this || idx < 0 || idx >= size || heap[idx] != node) {
            throw new IllegalArgumentException("foreign or invalidated handle");
        }
        return node;
    }

    private void ensureCapacity(int needed) {
        if (needed <= heap.length) {
            return;
        }
        int capacity = Math.max(needed, heap.length * 2);
        Node<T>[] grown = newNodeArray(capacity);
        for (int i = 0; i < size; i++) {
            grown[i] = heap[i];
        }
        heap = grown;
    }

    private int cmp(Node<T> a, Node<T> b) {
        return order.compare(a.item, b.item);
    }

    private void put(int p, Node<T> node) {
        heap[p] = node;
        node.index = p;
    }

    private void swap(int i, int j) {
        Node<T> a = heap[i];
        Node<T> b = heap[j];
        put(i, b);
        put(j, a);
    }

    private T removeAt(int p) {
        Node<T> removed = heap[p];
        int last = size - 1;
        Node<T> moved = heap[last];
        heap[last] = null;
        size = last;
        removed.index = -1;
        if (p != last) {
            put(p, moved);
            repair(p);
        }
        return removed.item;
    }

    /**
     * Restores the interval-heap invariants after the item at position p changed
     * (in either direction), or after a new item was placed at p.
     */
    private void repair(int p) {
        int lo = p & ~1;
        if (lo + 1 < size) {
            if (cmp(heap[lo], heap[lo + 1]) > 0) {
                swap(lo, lo + 1);
            }
            bubbleUpMin(lo);
            siftDownMin(lo);
            bubbleUpMax(lo + 1);
            siftDownMax(lo + 1);
        } else {
            // singleton node: its element must satisfy both heap constraints
            bubbleUpMin(lo);
            siftDownMin(lo);
            bubbleUpMax(lo);
            siftDownMax(lo);
        }
    }

    /** p is a low-side position; moves its value up while smaller than the parent low. */
    private void bubbleUpMin(int p) {
        int k = p >> 1;
        while (k > 0) {
            int parentLow = ((k - 1) >> 1) << 1;
            if (cmp(heap[p], heap[parentLow]) >= 0) {
                return;
            }
            swap(p, parentLow);
            p = parentLow;
            k = p >> 1;
        }
    }

    /** p is a high-side (or singleton) position; moves its value up while larger than the parent high. */
    private void bubbleUpMax(int p) {
        int k = p >> 1;
        while (k > 0) {
            int parentHigh = (((k - 1) >> 1) << 1) | 1;
            if (cmp(heap[p], heap[parentHigh]) <= 0) {
                return;
            }
            swap(p, parentHigh);
            p = parentHigh;
            k = p >> 1;
        }
    }

    /** p is a low-side position; sifts its value down the low tree, maintaining node intervals. */
    private void siftDownMin(int p) {
        while (true) {
            int k = p >> 1;
            int leftLow = 4 * k + 2;
            if (leftLow >= size) {
                return;
            }
            int m = leftLow;
            int rightLow = leftLow + 2;
            if (rightLow < size && cmp(heap[rightLow], heap[m]) < 0) {
                m = rightLow;
            }
            if (cmp(heap[p], heap[m]) <= 0) {
                return;
            }
            if (m + 1 < size) {
                // full child node (low = m, high = m + 1)
                Node<T> v = heap[p];
                if (cmp(v, heap[m + 1]) <= 0) {
                    Node<T> a = heap[m];
                    put(p, a);
                    put(m, v);
                } else {
                    Node<T> a = heap[m];
                    Node<T> b = heap[m + 1];
                    put(p, a);
                    put(m, b);
                    put(m + 1, v);
                }
                p = m;
            } else {
                // singleton child: it takes v, parent takes its old value
                swap(p, m);
                return;
            }
        }
    }

    /** p is a high-side position; sifts its value down the high tree, maintaining node intervals. */
    private void siftDownMax(int p) {
        while (true) {
            int k = p >> 1;
            int leftLow = 4 * k + 2;
            if (leftLow >= size) {
                return;
            }
            int m = highPosOf(leftLow);
            int rightLow = leftLow + 2;
            if (rightLow < size) {
                int h2 = highPosOf(rightLow);
                if (cmp(heap[h2], heap[m]) > 0) {
                    m = h2;
                }
            }
            if (cmp(heap[p], heap[m]) >= 0) {
                return;
            }
            int lowPos = m & ~1;
            if (m == lowPos) {
                // singleton child
                swap(p, m);
                return;
            }
            Node<T> v = heap[p];
            Node<T> a = heap[lowPos];
            Node<T> b = heap[m];
            put(p, b);
            if (cmp(v, a) >= 0) {
                put(m, v);
            } else {
                put(lowPos, v);
                put(m, a);
            }
            p = m;
        }
    }

    private int highPosOf(int lowPos) {
        return (lowPos + 1 < size) ? lowPos + 1 : lowPos;
    }
}

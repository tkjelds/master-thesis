/**
 * An interval heap that grows as needed. Empty extremum operations throw
 * immediately after acquiring the monitor; no operation waits for elements.
 */
@IntervalHeap.ThreadSafe
public final class IntervalHeap<T> {
    @interface ThreadSafe {}

    public interface Order<E> {
        int compare(E left, E right);
    }

    public static final class Handle<E> {
        private final IntervalHeap<E> owner;
        private E item;
        private int index = -1;

        private Handle(IntervalHeap<E> owner, E item) {
            this.owner = owner;
            this.item = item;
        }
    }

    private static final int MAX_CAPACITY = 0x7fffffff;
    private final Object monitor = new Object();
    private final Order<T> order;
    // Node k occupies slots 2*k and 2*k+1; the last high slot may be absent.
    private Handle<?>[] heap = new Handle<?>[16];
    private int size;

    public IntervalHeap(Order<T> order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        this.order = order;
    }

    public Handle<T> add(T item) {
        synchronized (monitor) {
            if (item == null) {
                throw new NullPointerException("item");
            }
            ensureCapacity();
            Handle<T> handle = new Handle<T>(this, item);
            insert(handle);
            return handle;
        }
    }

    public T findMin() {
        synchronized (monitor) {
            requireNonempty();
            return entry(0).item;
        }
    }

    public T findMax() {
        synchronized (monitor) {
            requireNonempty();
            return entry(size == 1 ? 0 : 1).item;
        }
    }

    public T deleteMin() {
        synchronized (monitor) {
            requireNonempty();
            return remove(entry(0));
        }
    }

    public T deleteMax() {
        synchronized (monitor) {
            requireNonempty();
            return remove(entry(size == 1 ? 0 : 1));
        }
    }

    public T delete(Handle<T> handle) {
        synchronized (monitor) {
            requireValid(handle);
            return remove(handle);
        }
    }

    public T replace(Handle<T> handle, T item) {
        synchronized (monitor) {
            if (item == null) {
                throw new NullPointerException("item");
            }
            requireValid(handle);
            T old = handle.item;
            detach(handle);
            handle.item = item;
            insert(handle);
            return old;
        }
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    private void requireNonempty() {
        if (size == 0) {
            throw new IllegalStateException("empty heap");
        }
    }

    private void requireValid(Handle<T> handle) {
        if (handle == null) {
            throw new NullPointerException("handle");
        }
        if (handle.owner != this || handle.index < 0) {
            throw new IllegalArgumentException("foreign or removed handle");
        }
    }

    @SuppressWarnings("unchecked")
    private Handle<T> entry(int index) {
        return (Handle<T>) heap[index];
    }

    private int compare(int left, int right) {
        return order.compare(entry(left).item, entry(right).item);
    }

    private void swap(int left, int right) {
        Handle<T> a = entry(left);
        Handle<T> b = entry(right);
        heap[left] = b;
        heap[right] = a;
        b.index = left;
        a.index = right;
    }

    private void ensureCapacity() {
        if (size < heap.length) {
            return;
        }
        if (heap.length == MAX_CAPACITY) {
            throw new OutOfMemoryError("heap capacity exhausted");
        }
        int capacity = heap.length <= MAX_CAPACITY / 2
                ? heap.length * 2 : MAX_CAPACITY;
        Handle<?>[] expanded = new Handle<?>[capacity];
        for (int i = 0; i < size; i++) {
            expanded[i] = heap[i];
        }
        heap = expanded;
    }

    private void insert(Handle<T> handle) {
        int index = size++;
        heap[index] = handle;
        handle.index = index;
        if (index == 0) {
            return;
        }
        if ((index & 1) != 0) {
            if (compare(index, index - 1) < 0) {
                swap(index, index - 1);
                rise(index - 1, false);
            } else {
                rise(index, true);
            }
        } else {
            int parentLow = (((index >>> 1) - 1) >>> 1) << 1;
            if (compare(index, parentLow) < 0) {
                rise(index, false);
            } else {
                rise(index, true);
            }
        }
    }

    private void rise(int index, boolean high) {
        while ((index >>> 1) != 0) {
            int parent = (((index >>> 1) - 1) >>> 1) << 1;
            if (high) {
                parent++;
            }
            int comparison = compare(index, parent);
            if (high ? comparison <= 0 : comparison >= 0) {
                return;
            }
            swap(index, parent);
            index = parent;
        }
    }

    private T remove(Handle<T> handle) {
        T item = handle.item;
        detach(handle);
        handle.item = null;
        return item;
    }

    private void detach(Handle<T> handle) {
        int index = handle.index;
        boolean high = (index & 1) != 0;
        // Promote the removed endpoint unconditionally, shifting ancestors down.
        while ((index >>> 1) != 0) {
            int parent = (((index >>> 1) - 1) >>> 1) << 1;
            if (high) {
                parent++;
            }
            swap(index, parent);
            index = parent;
        }
        int last = --size;
        Handle<T> tail = entry(last);
        heap[last] = null;
        if (index != last) {
            heap[index] = tail;
            tail.index = index;
            sink(high);
        }
        handle.index = -1;
    }

    private int endpoint(int node, boolean high) {
        int low = node << 1;
        return high && low + 1 < size ? low + 1 : low;
    }

    private void sink(boolean high) {
        int node = 0;
        int nodes = (size >>> 1) + (size & 1);
        while (true) {
            int low = node << 1;
            if (low + 1 < size && compare(low, low + 1) > 0) {
                // Crossing the interval also repairs the opposite heap:
                // its endpoint moves outward, within the parent's interval.
                swap(low, low + 1);
            }
            if (node >= nodes / 2) {
                return;
            }
            int child = node * 2 + 1;
            int best = endpoint(child, high);
            if (child + 1 < nodes) {
                int other = endpoint(child + 1, high);
                int comparison = compare(other, best);
                if (high ? comparison > 0 : comparison < 0) {
                    best = other;
                }
            }
            int current = endpoint(node, high);
            int comparison = compare(current, best);
            if (high ? comparison >= 0 : comparison <= 0) {
                return;
            }
            swap(current, best);
            node = best >>> 1;
        }
    }
}

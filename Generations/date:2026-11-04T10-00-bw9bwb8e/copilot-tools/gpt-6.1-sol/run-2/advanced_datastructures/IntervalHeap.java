/**
 * An unbounded, monitor-protected interval heap. Empty extremum operations
 * throw immediately; operations never wait for elements or spare capacity.
 * Elements must retain their ordering unless updated through replace.
 */
@IntervalHeap.ThreadSafe
public final class IntervalHeap<T> {
    @interface ThreadSafe {}

    public interface Order<E> {
        int compare(E left, E right);
    }

    /** Identifies one insertion, independently of its value or priority. */
    public static final class Handle<E> {
        private final IntervalHeap<E> owner;
        private E item;
        private int index;

        private Handle(IntervalHeap<E> owner, E item) {
            this.owner = owner;
            this.item = item;
            this.index = -1;
        }
    }

    private final Object monitor = new Object();
    private final Order<T> order;
    private Handle<?>[] entries = new Handle<?>[16];
    private int count;

    public IntervalHeap(Order<T> order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        this.order = order;
    }

    public Handle<T> add(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (monitor) {
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
            return entry(highIndex(0)).item;
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
            return remove(entry(highIndex(0)));
        }
    }

    public T delete(Handle<T> handle) {
        synchronized (monitor) {
            requireValid(handle);
            return remove(handle);
        }
    }

    public T replace(Handle<T> handle, T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (monitor) {
            requireValid(handle);
            T old = remove(handle);
            handle.item = item;
            insert(handle);
            return old;
        }
    }

    public int size() {
        synchronized (monitor) {
            return count;
        }
    }

    private void requireNonempty() {
        if (count == 0) {
            throw new IllegalStateException("Heap is empty");
        }
    }

    private void requireValid(Handle<T> handle) {
        if (handle == null) {
            throw new NullPointerException("handle");
        }
        if (handle.owner != this || handle.index < 0) {
            throw new IllegalArgumentException("Foreign or removed handle");
        }
    }

    @SuppressWarnings("unchecked")
    private Handle<T> entry(int index) {
        return (Handle<T>) entries[index];
    }

    private int highIndex(int node) {
        int high = node * 2 + 1;
        return high < count ? high : high - 1;
    }

    private void swap(int a, int b) {
        Handle<T> first = entry(a);
        Handle<T> second = entry(b);
        entries[a] = second;
        entries[b] = first;
        second.index = a;
        first.index = b;
    }

    private void ensureCapacity() {
        if (count < entries.length) {
            return;
        }
        if (count == 2147483647) {
            throw new OutOfMemoryError("Heap capacity exhausted");
        }
        int capacity = entries.length <= 1073741823
                ? entries.length * 2 : 2147483647;
        Handle<?>[] grown = new Handle<?>[capacity];
        for (int i = 0; i < count; i++) {
            grown[i] = entries[i];
        }
        entries = grown;
    }

    private void insert(Handle<T> handle) {
        ensureCapacity();
        int index = count++;
        entries[index] = handle;
        handle.index = index;
        int node = index / 2;
        if (node == 0) {
            if (index == 1 && order.compare(entry(0).item, handle.item) > 0) {
                swap(0, 1);
            }
            return;
        }

        if ((index & 1) != 0) {
            if (order.compare(handle.item, entry(index - 1).item) < 0) {
                swap(index, index - 1);
                bubbleUpLow(node);
            } else {
                bubbleUpHigh(node);
            }
        } else {
            int parent = (node - 1) / 2;
            int parentLow = parent * 2;
            int parentHigh = highIndex(parent);
            if (order.compare(handle.item, entry(parentLow).item) < 0) {
                swap(index, parentLow);
                bubbleUpLow(parent);
            } else if (order.compare(handle.item, entry(parentHigh).item) > 0) {
                swap(index, parentHigh);
                bubbleUpHigh(parent);
            }
        }
    }

    private void bubbleUpLow(int node) {
        while (node > 0) {
            int parent = (node - 1) / 2;
            int low = node * 2;
            int parentLow = parent * 2;
            if (order.compare(entry(low).item, entry(parentLow).item) >= 0) {
                return;
            }
            swap(low, parentLow);
            node = parent;
        }
    }

    private void bubbleUpHigh(int node) {
        while (node > 0) {
            int parent = (node - 1) / 2;
            int high = highIndex(node);
            int parentHigh = highIndex(parent);
            if (order.compare(entry(high).item, entry(parentHigh).item) <= 0) {
                return;
            }
            swap(high, parentHigh);
            node = parent;
        }
    }

    private T remove(Handle<T> handle) {
        T old = handle.item;
        boolean lowSide = (handle.index & 1) == 0;
        int node = handle.index / 2;

        // Move the target to a root endpoint without comparisons. Ancestor
        // endpoints shifted downward preserve every other interval and edge.
        while (node > 0) {
            int parent = (node - 1) / 2;
            swap(handle.index, lowSide ? parent * 2 : highIndex(parent));
            node = parent;
        }
        int root = handle.index;
        int lastIndex = --count;
        Handle<T> last = entry(lastIndex);
        entries[lastIndex] = null;
        handle.index = -1;
        handle.item = null;

        if (root < count) {
            entries[root] = last;
            last.index = root;
            if (count > 1 && order.compare(entry(0).item, entry(1).item) > 0) {
                swap(0, 1);
            }
            if (lowSide) {
                siftDownLow(0);
            } else {
                siftDownHigh(0);
            }
        }
        return old;
    }

    private void siftDownLow(int node) {
        int nodes = count / 2 + count % 2;
        while (node < nodes / 2) {
            int child = node * 2 + 1;
            int right = child + 1;
            if (right < nodes
                    && order.compare(entry(right * 2).item,
                                     entry(child * 2).item) < 0) {
                child = right;
            }
            int low = node * 2;
            int childLow = child * 2;
            if (order.compare(entry(low).item, entry(childLow).item) <= 0) {
                return;
            }
            swap(low, childLow);
            int childHigh = highIndex(child);
            if (order.compare(entry(childLow).item, entry(childHigh).item) > 0) {
                swap(childLow, childHigh);
            }
            node = child;
        }
    }

    private void siftDownHigh(int node) {
        int nodes = count / 2 + count % 2;
        while (node < nodes / 2) {
            int child = node * 2 + 1;
            int right = child + 1;
            if (right < nodes
                    && order.compare(entry(highIndex(right)).item,
                                     entry(highIndex(child)).item) > 0) {
                child = right;
            }
            int high = highIndex(node);
            int childHigh = highIndex(child);
            if (order.compare(entry(high).item, entry(childHigh).item) >= 0) {
                return;
            }
            swap(high, childHigh);
            int childLow = child * 2;
            if (order.compare(entry(childLow).item, entry(childHigh).item) > 0) {
                swap(childLow, childHigh);
            }
            node = child;
        }
    }
}

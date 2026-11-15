interface Order<T> {
    int compare(T left, T right);
}

/* Opaque insertion handle. Only handles created by IntervalHeap are valid. */
@Handle.ThreadSafe
abstract class Handle<T> {
    @interface ThreadSafe {}

    Handle() {
    }
}

@IntervalHeap.ThreadSafe
public final class IntervalHeap<T> {
    @interface ThreadSafe {}

    private static final class Entry<E> extends Handle<E> {
        private IntervalHeap<?> owner;
        private E item;
        private int index;

        private Entry(IntervalHeap<?> owner, E item, int index) {
            this.owner = owner;
            this.item = item;
            this.index = index;
        }
    }

    private final Object lock = new Object();
    private final Order<T> order;
    private Object[] entries = new Object[16];
    private int count;

    public IntervalHeap(Order<T> order) {
        if (order == null) {
            throw new NullPointerException();
        }
        this.order = order;
    }

    public Handle<T> add(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (lock) {
            ensureCapacity();
            Entry<T> entry = new Entry<T>(this, item, count);
            entries[count] = entry;
            count++;
            repair(entry.index / 2);
            return entry;
        }
    }

    public T findMin() {
        synchronized (lock) {
            requireNonempty();
            return entryAt(0).item;
        }
    }

    public T findMax() {
        synchronized (lock) {
            requireNonempty();
            return entryAt(count == 1 ? 0 : 1).item;
        }
    }

    public T deleteMin() {
        synchronized (lock) {
            requireNonempty();
            return removeAt(0);
        }
    }

    public T deleteMax() {
        synchronized (lock) {
            requireNonempty();
            return removeAt(count == 1 ? 0 : 1);
        }
    }

    public T delete(Handle<T> handle) {
        if (handle == null) {
            throw new NullPointerException();
        }
        synchronized (lock) {
            Entry<T> entry = checkedEntry(handle);
            return removeAt(entry.index);
        }
    }

    public T replace(Handle<T> handle, T item) {
        if (handle == null || item == null) {
            throw new NullPointerException();
        }
        synchronized (lock) {
            Entry<T> entry = checkedEntry(handle);
            T old = entry.item;
            entry.item = item;
            repair(entry.index / 2);
            return old;
        }
    }

    public int size() {
        synchronized (lock) {
            return count;
        }
    }

    private void requireNonempty() {
        if (count == 0) {
            throw new IllegalStateException();
        }
    }

    @SuppressWarnings("unchecked")
    private Entry<T> entryAt(int index) {
        return (Entry<T>) entries[index];
    }

    @SuppressWarnings("unchecked")
    private Entry<T> checkedEntry(Handle<T> handle) {
        if (!(handle instanceof Entry<?>)) {
            throw new IllegalArgumentException();
        }
        Entry<?> entry = (Entry<?>) handle;
        if (entry.owner != this || entry.index < 0) {
            throw new IllegalArgumentException();
        }
        return (Entry<T>) entry;
    }

    private void ensureCapacity() {
        if (count < entries.length) {
            return;
        }
        if (count == Integer.MAX_VALUE) {
            throw new OutOfMemoryError();
        }
        long proposed = (long) entries.length + entries.length / 2 + 1;
        int newLength = proposed > Integer.MAX_VALUE
                ? Integer.MAX_VALUE : (int) proposed;
        Object[] grown = new Object[newLength];
        for (int i = 0; i < count; i++) {
            grown[i] = entries[i];
        }
        entries = grown;
    }

    private T removeAt(int index) {
        Entry<T> removed = entryAt(index);
        T old = removed.item;
        int last = count - 1;

        if (index != last) {
            Entry<T> moved = entryAt(last);
            entries[index] = moved;
            moved.index = index;
        }
        entries[last] = null;
        count = last;

        removed.owner = null;
        removed.index = -1;
        removed.item = null;

        if (index < count) {
            repair(index / 2);
        }
        return old;
    }

    private int lowIndex(int node) {
        return node * 2;
    }

    private int highIndex(int node) {
        int low = lowIndex(node);
        return low + 1 < count ? low + 1 : low;
    }

    private int compareAt(int left, int right) {
        return order.compare(entryAt(left).item, entryAt(right).item);
    }

    private void swap(int left, int right) {
        if (left == right) {
            return;
        }
        Entry<T> a = entryAt(left);
        Entry<T> b = entryAt(right);
        entries[left] = b;
        entries[right] = a;
        b.index = left;
        a.index = right;
    }

    private void normalize(int node) {
        int low = lowIndex(node);
        int high = highIndex(node);
        if (low != high && compareAt(low, high) > 0) {
            swap(low, high);
        }
    }

    /*
     * Only one node initially has changed endpoints. Normalize it, repair
     * upward violations, then repair downward violations. During a downward
     * repair, normalizing a child preserves the opposite endpoint heap.
     */
    private void repair(int node) {
        normalize(node);
        bubbleLow(node);
        bubbleHigh(node);
        siftLow(node);
        siftHigh(node);
    }

    private void bubbleLow(int node) {
        while (node > 0) {
            int parent = (node - 1) / 2;
            int childLow = lowIndex(node);
            int parentLow = lowIndex(parent);
            if (compareAt(childLow, parentLow) >= 0) {
                break;
            }
            swap(childLow, parentLow);
            node = parent;
        }
    }

    private void bubbleHigh(int node) {
        while (node > 0) {
            int parent = (node - 1) / 2;
            int childHigh = highIndex(node);
            int parentHigh = highIndex(parent);
            if (compareAt(childHigh, parentHigh) <= 0) {
                break;
            }
            swap(childHigh, parentHigh);
            node = parent;
        }
    }

    private void siftLow(int node) {
        int nodes = (count >>> 1) + (count & 1);
        while (true) {
            long leftLong = (long) node * 2 + 1;
            if (leftLong >= nodes) {
                return;
            }
            int child = (int) leftLong;
            int right = child + 1;
            if (right < nodes
                    && compareAt(lowIndex(right), lowIndex(child)) < 0) {
                child = right;
            }
            if (compareAt(lowIndex(node), lowIndex(child)) <= 0) {
                return;
            }
            swap(lowIndex(node), lowIndex(child));
            normalize(child);
            node = child;
        }
    }

    private void siftHigh(int node) {
        int nodes = (count >>> 1) + (count & 1);
        while (true) {
            long leftLong = (long) node * 2 + 1;
            if (leftLong >= nodes) {
                return;
            }
            int child = (int) leftLong;
            int right = child + 1;
            if (right < nodes
                    && compareAt(highIndex(right), highIndex(child)) > 0) {
                child = right;
            }
            if (compareAt(highIndex(node), highIndex(child)) >= 0) {
                return;
            }
            swap(highIndex(node), highIndex(child));
            normalize(child);
            node = child;
        }
    }
}

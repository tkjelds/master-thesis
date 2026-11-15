interface Order<T> {
    int compare(T left, T right);
}

/**
 * An unbounded interval heap. Empty extremum operations throw immediately;
 * operations do not wait for elements or capacity.
 */
@IntervalHeap.ThreadSafe
public final class IntervalHeap<T> {
    @interface ThreadSafe {}

    public static final class Handle<E> {
        private final IntervalHeap<E> owner;
        private E item;
        private int index;

        private Handle(IntervalHeap<E> owner, E item, int index) {
            this.owner = owner;
            this.item = item;
            this.index = index;
        }
    }

    private final Object lock = new Object();
    private final Order<T> order;
    private Object[] entries = new Object[8];
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
            Handle<T> handle = new Handle<T>(this, item, count);
            entries[count++] = handle;
            repair((count - 1) / 2);
            return handle;
        }
    }

    public T findMin() {
        synchronized (lock) {
            requireNonempty();
            return entry(0).item;
        }
    }

    public T findMax() {
        synchronized (lock) {
            requireNonempty();
            return entry(count == 1 ? 0 : 1).item;
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
            validate(handle);
            return removeAt(handle.index);
        }
    }

    public T replace(Handle<T> handle, T item) {
        if (handle == null || item == null) {
            throw new NullPointerException();
        }
        synchronized (lock) {
            validate(handle);
            T old = handle.item;
            handle.item = item;
            repair(handle.index / 2);
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
            throw new IllegalStateException("Heap is empty");
        }
    }

    private void validate(Handle<T> handle) {
        if (handle.owner != this || handle.index < 0
                || handle.index >= count || entries[handle.index] != handle) {
            throw new IllegalArgumentException("Invalid handle");
        }
    }

    private void ensureCapacity() {
        if (count < entries.length) {
            return;
        }
        if (count == Integer.MAX_VALUE) {
            throw new IllegalStateException("Heap is too large");
        }
        long doubled = (long) entries.length * 2;
        int capacity = doubled > Integer.MAX_VALUE
                ? Integer.MAX_VALUE : (int) doubled;
        Object[] grown = new Object[capacity];
        System.arraycopy(entries, 0, grown, 0, count);
        entries = grown;
    }

    @SuppressWarnings("unchecked")
    private Handle<T> entry(int index) {
        return (Handle<T>) entries[index];
    }

    private int compare(int left, int right) {
        return order.compare(entry(left).item, entry(right).item);
    }

    private void swap(int left, int right) {
        Handle<T> a = entry(left);
        Handle<T> b = entry(right);
        entries[left] = b;
        entries[right] = a;
        a.index = right;
        b.index = left;
    }

    private int high(int node) {
        int low = node * 2;
        return low + 1 < count ? low + 1 : low;
    }

    private T removeAt(int index) {
        Handle<T> removed = entry(index);
        T item = removed.item;
        int last = --count;
        if (index != last) {
            Handle<T> moved = entry(last);
            entries[index] = moved;
            moved.index = index;
        }
        entries[last] = null;
        removed.index = -1;
        removed.item = null;
        if (index < count) {
            repair(index / 2);
        }
        return item;
    }

    private void repair(int node) {
        int low = node * 2;
        int hi = high(node);
        if (low != hi && compare(low, hi) > 0) {
            swap(low, hi);
        }
        repairLow(node);
        repairHigh(node);
    }

    private void repairLow(int node) {
        if (node > 0 && compare(node * 2, ((node - 1) / 2) * 2) < 0) {
            while (node > 0) {
                int parent = (node - 1) / 2;
                if (compare(node * 2, parent * 2) >= 0) {
                    break;
                }
                swap(node * 2, parent * 2);
                node = parent;
            }
            return;
        }
        while (true) {
            long first = (long) node * 2 + 1;
            if (first * 2 >= count) {
                return;
            }
            int child = (int) first;
            int right = child + 1;
            if ((long) right * 2 < count
                    && compare(right * 2, child * 2) < 0) {
                child = right;
            }
            if (compare(node * 2, child * 2) <= 0) {
                return;
            }
            swap(node * 2, child * 2);
            int childLow = child * 2;
            int childHigh = high(child);
            if (childLow != childHigh && compare(childLow, childHigh) > 0) {
                swap(childLow, childHigh);
            }
            node = child;
        }
    }

    private void repairHigh(int node) {
        if (node > 0 && compare(high(node), high((node - 1) / 2)) > 0) {
            while (node > 0) {
                int parent = (node - 1) / 2;
                if (compare(high(node), high(parent)) <= 0) {
                    break;
                }
                swap(high(node), high(parent));
                node = parent;
            }
            return;
        }
        while (true) {
            long first = (long) node * 2 + 1;
            if (first * 2 >= count) {
                return;
            }
            int child = (int) first;
            int right = child + 1;
            if ((long) right * 2 < count
                    && compare(high(right), high(child)) > 0) {
                child = right;
            }
            if (compare(high(node), high(child)) >= 0) {
                return;
            }
            swap(high(node), high(child));
            int childLow = child * 2;
            int childHigh = high(child);
            if (childLow != childHigh && compare(childLow, childHigh) > 0) {
                swap(childLow, childHigh);
            }
            node = child;
        }
    }
}

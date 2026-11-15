@IntervalHeap.ThreadSafe
public final class IntervalHeap<T> {
    @interface ThreadSafe {}

    public interface Order<T> {
        int compare(T left, T right);
    }

    public static final class Handle<T> {
        private final IntervalHeap<T> owner;
        private T item;
        private int index;

        private Handle(IntervalHeap<T> owner, T item, int index) {
            this.owner = owner;
            this.item = item;
            this.index = index;
        }
    }

    private final Order<T> order;
    private final Object lock = new Object();
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
        synchronized (lock) {
            ensureCapacity();
            Handle<T> handle = new Handle<T>(this, item, count);
            entries[count++] = handle;
            repair(handle.index);
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
            throw new NullPointerException("handle");
        }
        synchronized (lock) {
            validate(handle);
            return removeAt(handle.index);
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
            validate(handle);
            T old = handle.item;
            handle.item = item;
            repair(handle.index);
            return old;
        }
    }

    public int size() {
        synchronized (lock) {
            return count;
        }
    }

    @SuppressWarnings("unchecked")
    private Handle<T> entry(int index) {
        return (Handle<T>) entries[index];
    }

    private void requireNonempty() {
        if (count == 0) {
            throw new IllegalStateException("heap is empty");
        }
    }

    private void validate(Handle<T> handle) {
        if (handle.owner != this || handle.index < 0) {
            throw new IllegalArgumentException("foreign or invalid handle");
        }
    }

    private void ensureCapacity() {
        if (count < entries.length) {
            return;
        }
        int capacity = entries.length;
        if (capacity == 2147483647) {
            throw new IllegalStateException("heap capacity exhausted");
        }
        int newCapacity = capacity > 1073741823 ? 2147483647 : capacity * 2;
        Handle<?>[] grown = new Handle<?>[newCapacity];
        for (int i = 0; i < count; i++) {
            grown[i] = entries[i];
        }
        entries = grown;
    }

    private T removeAt(int index) {
        Handle<T> removed = entry(index);
        T item = removed.item;
        int last = --count;
        Handle<T> moved = entry(last);
        entries[last] = null;
        removed.index = -1;
        removed.item = null;
        if (index != last) {
            entries[index] = moved;
            moved.index = index;
            repair(index);
        }
        return item;
    }

    private int compare(int left, int right) {
        return order.compare(entry(left).item, entry(right).item);
    }

    private void swap(int left, int right) {
        Handle<T> a = entry(left);
        Handle<T> b = entry(right);
        entries[left] = b;
        entries[right] = a;
        b.index = left;
        a.index = right;
    }

    private int high(int node) {
        int low = node * 2;
        return low + 1 < count ? low + 1 : low;
    }

    private void repair(int index) {
        int node = index / 2;
        int low = node * 2;
        int high = high(node);

        if (low == high) {
            if (node != 0) {
                int parent = (node - 1) / 2;
                if (compare(low, parent * 2) < 0) {
                    minUp(node);
                } else if (compare(low, high(parent)) > 0) {
                    maxUp(node);
                }
            }
            return;
        }

        if (compare(low, high) > 0) {
            swap(low, high);
            if (index == low) {
                maxUp(node);
                minDown(node);
            } else {
                minUp(node);
                maxDown(node);
            }
        } else if (index == low) {
            if (node != 0 && compare(low, ((node - 1) / 2) * 2) < 0) {
                minUp(node);
            } else {
                minDown(node);
            }
        } else {
            if (node != 0 && compare(high, high((node - 1) / 2)) > 0) {
                maxUp(node);
            } else {
                maxDown(node);
            }
        }
    }

    private void minUp(int node) {
        while (node != 0) {
            int parent = (node - 1) / 2;
            if (compare(node * 2, parent * 2) >= 0) {
                return;
            }
            swap(node * 2, parent * 2);
            node = parent;
        }
    }

    private void maxUp(int node) {
        while (node != 0) {
            int parent = (node - 1) / 2;
            if (compare(high(node), high(parent)) <= 0) {
                return;
            }
            swap(high(node), high(parent));
            node = parent;
        }
    }

    private void minDown(int node) {
        int nodes = count / 2 + count % 2;
        while ((long) node * 2 + 1 < nodes) {
            int child = node * 2 + 1;
            if (child + 1 < nodes && compare((child + 1) * 2, child * 2) < 0) {
                child++;
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

    private void maxDown(int node) {
        int nodes = count / 2 + count % 2;
        while ((long) node * 2 + 1 < nodes) {
            int child = node * 2 + 1;
            if (child + 1 < nodes && compare(high(child + 1), high(child)) > 0) {
                child++;
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

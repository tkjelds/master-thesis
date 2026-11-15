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
    private Object[] entries = new Object[16];
    private int count;

    public IntervalHeap(Order<T> order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        this.order = order;
    }

    // Storage grows as needed; empty extremum operations do not wait for items.
    public Handle<T> add(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            ensureCapacity();
            int index = count;
            Handle<T> handle = new Handle<T>(this, item, index);
            entries[index] = handle;
            count = index + 1;

            int node = index / 2;
            if ((index & 1) != 0) {
                if (compare(index, index - 1) < 0) {
                    swap(index, index - 1);
                    siftLowUp(node);
                } else {
                    siftHighUp(node);
                }
            } else if (node != 0) {
                int parent = (node - 1) / 2;
                if (compare(index, lowIndex(parent)) < 0) {
                    siftLowUp(node);
                } else if (compare(index, highIndex(parent)) > 0) {
                    siftHighUp(node);
                }
            }
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
            return entry(highIndex(0)).item;
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
            return removeAt(highIndex(0));
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

    private void ensureCapacity() {
        if (count < entries.length) {
            return;
        }
        if (entries.length == Integer.MAX_VALUE) {
            throw new OutOfMemoryError("interval heap is too large");
        }
        int capacity = entries.length <= Integer.MAX_VALUE / 2
                ? entries.length * 2 : Integer.MAX_VALUE;
        Object[] grown = new Object[capacity];
        for (int i = 0; i < count; i++) {
            grown[i] = entries[i];
        }
        entries = grown;
    }

    @SuppressWarnings("unchecked")
    private Handle<T> entry(int index) {
        return (Handle<T>) entries[index];
    }

    private int compare(int left, int right) {
        return order.compare(entry(left).item, entry(right).item);
    }

    private int lowIndex(int node) {
        return node * 2;
    }

    private int highIndex(int node) {
        int low = lowIndex(node);
        return low + 1 < count ? low + 1 : low;
    }

    private void swap(int a, int b) {
        Handle<T> left = entry(a);
        Handle<T> right = entry(b);
        entries[a] = right;
        entries[b] = left;
        right.index = a;
        left.index = b;
    }

    private void requireNonempty() {
        if (count == 0) {
            throw new IllegalStateException("interval heap is empty");
        }
    }

    private void validate(Handle<T> handle) {
        if (handle.owner != this || handle.index < 0) {
            throw new IllegalArgumentException("foreign or invalid handle");
        }
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
        if (index != last) {
            repair(index);
        }
        return item;
    }

    private void repair(int index) {
        int node = index / 2;
        int low = lowIndex(node);
        int high = highIndex(node);

        if (low == high) {
            if (node != 0) {
                int parent = (node - 1) / 2;
                if (compare(low, lowIndex(parent)) < 0) {
                    siftLowUp(node);
                } else if (compare(low, highIndex(parent)) > 0) {
                    siftHighUp(node);
                }
            }
        } else if (index == low) {
            if (compare(low, high) > 0) {
                swap(low, high);
                siftLowDown(node);
                siftHighUp(node);
            } else if (node != 0
                    && compare(low, lowIndex((node - 1) / 2)) < 0) {
                siftLowUp(node);
            } else {
                siftLowDown(node);
            }
        } else {
            if (compare(low, high) > 0) {
                swap(low, high);
                siftHighDown(node);
                siftLowUp(node);
            } else if (node != 0
                    && compare(high, highIndex((node - 1) / 2)) > 0) {
                siftHighUp(node);
            } else {
                siftHighDown(node);
            }
        }
    }

    private void siftLowUp(int node) {
        while (node != 0) {
            int parent = (node - 1) / 2;
            int childIndex = lowIndex(node);
            int parentIndex = lowIndex(parent);
            if (compare(childIndex, parentIndex) >= 0) {
                return;
            }
            swap(childIndex, parentIndex);
            node = parent;
        }
    }

    private void siftHighUp(int node) {
        while (node != 0) {
            int parent = (node - 1) / 2;
            int childIndex = highIndex(node);
            int parentIndex = highIndex(parent);
            if (compare(childIndex, parentIndex) <= 0) {
                return;
            }
            swap(childIndex, parentIndex);
            node = parent;
        }
    }

    private void siftLowDown(int node) {
        int nodes = (count >>> 1) + (count & 1);
        while (node < nodes / 2) {
            int child = node * 2 + 1;
            int right = child + 1;
            if (right < nodes && compare(lowIndex(right), lowIndex(child)) < 0) {
                child = right;
            }
            int childLow = lowIndex(child);
            int nodeLow = lowIndex(node);
            if (compare(nodeLow, childLow) <= 0) {
                return;
            }
            swap(nodeLow, childLow);
            int childHigh = highIndex(child);
            if (childLow != childHigh && compare(childLow, childHigh) > 0) {
                swap(childLow, childHigh);
            }
            node = child;
        }
    }

    private void siftHighDown(int node) {
        int nodes = (count >>> 1) + (count & 1);
        while (node < nodes / 2) {
            int child = node * 2 + 1;
            int right = child + 1;
            if (right < nodes && compare(highIndex(right), highIndex(child)) > 0) {
                child = right;
            }
            int childHigh = highIndex(child);
            int nodeHigh = highIndex(node);
            if (compare(nodeHigh, childHigh) >= 0) {
                return;
            }
            swap(nodeHigh, childHigh);
            int childLow = lowIndex(child);
            if (childLow != childHigh && compare(childLow, childHigh) > 0) {
                swap(childLow, childHigh);
            }
            node = child;
        }
    }
}

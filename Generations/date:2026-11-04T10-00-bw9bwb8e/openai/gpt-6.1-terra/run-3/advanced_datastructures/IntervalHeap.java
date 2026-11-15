@IntervalHeap.ThreadSafe
public final class IntervalHeap<T> {
    @interface ThreadSafe {}

    public interface Order<T> {
        int compare(T left, T right);
    }

    public static final class Handle<T> {
        private final IntervalHeap<?> owner;
        private int index;
        private T item;

        private Handle(IntervalHeap<?> owner, int index, T item) {
            this.owner = owner;
            this.index = index;
            this.item = item;
        }
    }

    private final Object monitor = new Object();
    private final Order<T> order;
    private Object[] entries;
    private int count;

    public IntervalHeap(Order<T> order) {
        if (order == null) {
            throw new NullPointerException();
        }
        this.order = order;
        this.entries = new Object[8];
    }

    public Handle<T> add(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (monitor) {
            ensureCapacity();
            Handle<T> handle = new Handle<T>(this, count, item);
            entries[count] = handle;
            count++;
            repairNode(handle.index / 2);
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
            return entry(count == 1 ? 0 : 1).item;
        }
    }

    public T deleteMin() {
        synchronized (monitor) {
            requireNonempty();
            return removeAt(0);
        }
    }

    public T deleteMax() {
        synchronized (monitor) {
            requireNonempty();
            return removeAt(count == 1 ? 0 : 1);
        }
    }

    public T delete(Handle<T> handle) {
        if (handle == null) {
            throw new NullPointerException();
        }
        synchronized (monitor) {
            validate(handle);
            return removeAt(handle.index);
        }
    }

    public T replace(Handle<T> handle, T item) {
        if (handle == null || item == null) {
            throw new NullPointerException();
        }
        synchronized (monitor) {
            validate(handle);
            T old = handle.item;
            handle.item = item;
            repairNode(handle.index / 2);
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

    private void validate(Handle<T> handle) {
        if (handle.owner != this || handle.index < 0) {
            throw new IllegalArgumentException("Foreign or invalid handle");
        }
    }

    private void ensureCapacity() {
        if (count < entries.length) {
            return;
        }
        if (count == Integer.MAX_VALUE) {
            throw new OutOfMemoryError("Heap is too large");
        }
        long grown = (long) entries.length + (entries.length / 2) + 1;
        int capacity = grown > Integer.MAX_VALUE
                ? Integer.MAX_VALUE : (int) grown;
        Object[] replacement = new Object[capacity];
        for (int i = 0; i < count; i++) {
            replacement[i] = entries[i];
        }
        entries = replacement;
    }

    @SuppressWarnings("unchecked")
    private Handle<T> entry(int index) {
        return (Handle<T>) entries[index];
    }

    private int compare(int left, int right) {
        return order.compare(entry(left).item, entry(right).item);
    }

    private void swap(int left, int right) {
        if (left == right) {
            return;
        }
        Handle<T> a = entry(left);
        Handle<T> b = entry(right);
        entries[left] = b;
        entries[right] = a;
        b.index = left;
        a.index = right;
    }

    private int lowIndex(int node) {
        return node * 2;
    }

    private int highIndex(int node) {
        int low = lowIndex(node);
        return low + 1 < count ? low + 1 : low;
    }

    private void normalize(int node) {
        int low = lowIndex(node);
        int high = highIndex(node);
        if (low != high && compare(low, high) > 0) {
            swap(low, high);
        }
    }

    private T removeAt(int index) {
        Handle<T> removed = entry(index);
        T result = removed.item;
        int last = count - 1;

        if (index != last) {
            Handle<T> moved = entry(last);
            entries[index] = moved;
            moved.index = index;
        }
        entries[last] = null;
        count = last;
        removed.index = -1;
        removed.item = null;

        if (index < count) {
            repairNode(index / 2);
        }
        return result;
    }

    private void repairNode(int node) {
        normalize(node);
        repairLow(node);
        repairHigh(node);
    }

    private void repairLow(int node) {
        if (node > 0) {
            int parent = (node - 1) / 2;
            if (compare(lowIndex(node), lowIndex(parent)) < 0) {
                do {
                    swap(lowIndex(node), lowIndex(parent));
                    node = parent;
                    if (node == 0) {
                        return;
                    }
                    parent = (node - 1) / 2;
                } while (compare(lowIndex(node), lowIndex(parent)) < 0);
                return;
            }
        }

        int nodeCount = count / 2 + count % 2;
        while (true) {
            long leftLong = 2L * node + 1;
            if (leftLong >= nodeCount) {
                return;
            }
            int child = (int) leftLong;
            int right = child + 1;
            if (right < nodeCount
                    && compare(lowIndex(right), lowIndex(child)) < 0) {
                child = right;
            }
            if (compare(lowIndex(child), lowIndex(node)) >= 0) {
                return;
            }
            swap(lowIndex(node), lowIndex(child));
            normalize(child);
            node = child;
        }
    }

    private void repairHigh(int node) {
        if (node > 0) {
            int parent = (node - 1) / 2;
            if (compare(highIndex(node), highIndex(parent)) > 0) {
                do {
                    swap(highIndex(node), highIndex(parent));
                    node = parent;
                    if (node == 0) {
                        return;
                    }
                    parent = (node - 1) / 2;
                } while (compare(highIndex(node), highIndex(parent)) > 0);
                return;
            }
        }

        int nodeCount = count / 2 + count % 2;
        while (true) {
            long leftLong = 2L * node + 1;
            if (leftLong >= nodeCount) {
                return;
            }
            int child = (int) leftLong;
            int right = child + 1;
            if (right < nodeCount
                    && compare(highIndex(right), highIndex(child)) > 0) {
                child = right;
            }
            if (compare(highIndex(child), highIndex(node)) <= 0) {
                return;
            }
            swap(highIndex(node), highIndex(child));
            normalize(child);
            node = child;
        }
    }
}

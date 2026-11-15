@IntervalHeap.ThreadSafe
public final class IntervalHeap<T> {
    @interface ThreadSafe {}

    public interface Order<E> {
        int compare(E left, E right);
    }

    public static final class Handle<E> {
        private final IntervalHeap<?> owner;
        private E item;
        private int index;
        private boolean live;

        private Handle(IntervalHeap<?> owner, E item, int index) {
            this.owner = owner;
            this.item = item;
            this.index = index;
            this.live = true;
        }
    }

    private final Object lock = new Object();
    private final Order<T> order;
    private Object[] heap = new Object[8];
    private int count;

    public IntervalHeap(Order<T> order) {
        if (order == null) {
            throw new NullPointerException();
        }
        this.order = order;
    }

    public Handle<T> add(T item) {
        synchronized (lock) {
            if (item == null) {
                throw new NullPointerException();
            }
            ensureCapacity();

            int index = count;
            Handle<T> handle = new Handle<T>(this, item, index);
            heap[index] = handle;
            count++;

            int node = index >>> 1;
            if ((index & 1) == 0) {
                // A new singleton can violate either parent heap.
                repairSingleton(node);
            } else {
                // The previous singleton becomes a full interval.
                int low = index - 1;
                if (compare(low, index) > 0) {
                    swap(low, index);
                    siftUpLow(node);
                } else {
                    siftUpHigh(node);
                }
            }
            return handle;
        }
    }

    public T findMin() {
        synchronized (lock) {
            requireNonEmpty();
            return entry(0).item;
        }
    }

    public T findMax() {
        synchronized (lock) {
            requireNonEmpty();
            return entry(count == 1 ? 0 : 1).item;
        }
    }

    public T deleteMin() {
        synchronized (lock) {
            requireNonEmpty();
            return removeAt(0);
        }
    }

    public T deleteMax() {
        synchronized (lock) {
            requireNonEmpty();
            return removeAt(count == 1 ? 0 : 1);
        }
    }

    public T delete(Handle<T> handle) {
        synchronized (lock) {
            validate(handle);
            return removeAt(handle.index);
        }
    }

    public T replace(Handle<T> handle, T item) {
        synchronized (lock) {
            if (handle == null || item == null) {
                throw new NullPointerException();
            }
            validate(handle);

            T old = handle.item;
            int index = handle.index;
            handle.item = item;
            repair(index);
            return old;
        }
    }

    public int size() {
        synchronized (lock) {
            return count;
        }
    }

    private void requireNonEmpty() {
        if (count == 0) {
            throw new IllegalStateException();
        }
    }

    private void validate(Handle<T> handle) {
        if (handle == null) {
            throw new NullPointerException();
        }
        if (handle.owner != this || !handle.live) {
            throw new IllegalArgumentException();
        }
    }

    private void ensureCapacity() {
        if (count < heap.length) {
            return;
        }
        if (count == Integer.MAX_VALUE) {
            throw new OutOfMemoryError();
        }

        long proposed = (long) heap.length * 2;
        int capacity = proposed > Integer.MAX_VALUE
                ? Integer.MAX_VALUE : (int) proposed;
        Object[] larger = new Object[capacity];
        for (int i = 0; i < count; i++) {
            larger[i] = heap[i];
        }
        heap = larger;
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

    private int highIndex(int node) {
        int low = node * 2;
        return low + 1 < count ? low + 1 : low;
    }

    private T removeAt(int index) {
        Handle<T> removed = entry(index);
        T result = removed.item;
        int last = count - 1;

        if (index != last) {
            Handle<T> moved = entry(last);
            heap[index] = moved;
            moved.index = index;
        }
        heap[last] = null;
        count = last;

        removed.live = false;
        removed.index = -1;
        removed.item = null;

        if (index < count) {
            repair(index);
        }
        return result;
    }

    private void repair(int index) {
        int node = index >>> 1;
        int low = node * 2;
        int high = highIndex(node);

        if (low == high) {
            repairSingleton(node);
        } else if ((index & 1) == 0) {
            if (compare(low, high) > 0) {
                swap(low, high);
                siftDownLow(node);
                siftUpHigh(node);
            } else if (node > 0 && compare(low, ((node - 1) >>> 1) * 2) < 0) {
                siftUpLow(node);
            } else {
                siftDownLow(node);
            }
        } else {
            if (compare(low, high) > 0) {
                swap(low, high);
                siftDownHigh(node);
                siftUpLow(node);
            } else if (node > 0
                    && compare(high, highIndex((node - 1) >>> 1)) > 0) {
                siftUpHigh(node);
            } else {
                siftDownHigh(node);
            }
        }
    }

    private void repairSingleton(int node) {
        if (node == 0) {
            return;
        }
        int index = node * 2;
        int parent = (node - 1) >>> 1;
        if (compare(index, parent * 2) < 0) {
            siftUpLow(node);
        } else if (compare(index, highIndex(parent)) > 0) {
            siftUpHigh(node);
        }
    }

    private void siftUpLow(int node) {
        while (node > 0) {
            int parent = (node - 1) >>> 1;
            int index = node * 2;
            int parentIndex = parent * 2;
            if (compare(index, parentIndex) >= 0) {
                break;
            }
            swap(index, parentIndex);
            node = parent;
        }
    }

    private void siftUpHigh(int node) {
        while (node > 0) {
            int parent = (node - 1) >>> 1;
            int index = highIndex(node);
            int parentIndex = highIndex(parent);
            if (compare(index, parentIndex) <= 0) {
                break;
            }
            swap(index, parentIndex);
            node = parent;
        }
    }

    private void siftDownLow(int node) {
        int nodes = (count >>> 1) + (count & 1);
        while (true) {
            long firstChild = (long) node * 2 + 1;
            if (firstChild >= nodes) {
                return;
            }
            int child = (int) firstChild;
            if (child + 1 < nodes && compare((child + 1) * 2, child * 2) < 0) {
                child++;
            }

            int index = node * 2;
            int childLow = child * 2;
            if (compare(index, childLow) <= 0) {
                return;
            }
            swap(index, childLow);

            int childHigh = highIndex(child);
            if (childLow != childHigh && compare(childLow, childHigh) > 0) {
                swap(childLow, childHigh);
            }
            node = child;
        }
    }

    private void siftDownHigh(int node) {
        int nodes = (count >>> 1) + (count & 1);
        while (true) {
            long firstChild = (long) node * 2 + 1;
            if (firstChild >= nodes) {
                return;
            }
            int child = (int) firstChild;
            if (child + 1 < nodes
                    && compare(highIndex(child + 1), highIndex(child)) > 0) {
                child++;
            }

            int index = highIndex(node);
            int childHigh = highIndex(child);
            if (compare(index, childHigh) >= 0) {
                return;
            }
            swap(index, childHigh);

            int childLow = child * 2;
            if (childLow != childHigh && compare(childLow, childHigh) > 0) {
                swap(childLow, childHigh);
            }
            node = child;
        }
    }
}

/**
 * A growable, monitor-protected interval heap. Empty extremum operations throw
 * IllegalStateException; operations never wait for elements or capacity.
 * Node k occupies slots 2*k and 2*k+1. A final unpaired slot is both endpoints.
 */
@IntervalHeap.ThreadSafe
public final class IntervalHeap<T> {
    @interface ThreadSafe {}

    public interface Order<T> {
        int compare(T left, T right);
    }

    public static final class Handle<T> {
        private final IntervalHeap<T> owner;
        private T item;
        private int index = -1;

        private Handle(IntervalHeap<T> owner, T item) {
            this.owner = owner;
            this.item = item;
        }
    }

    private final Object monitor = new Object();
    private final Order<T> order;
    private Handle<T>[] entries;
    private int count;

    @SuppressWarnings("unchecked")
    public IntervalHeap(Order<T> order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        this.order = order;
        entries = (Handle<T>[]) new Handle<?>[16];
    }

    public Handle<T> add(T item) {
        synchronized (monitor) {
            if (item == null) {
                throw new NullPointerException("item");
            }
            Handle<T> handle = new Handle<T>(this, item);
            insert(handle);
            return handle;
        }
    }

    public T findMin() {
        synchronized (monitor) {
            requireNotEmpty();
            return entries[0].item;
        }
    }

    public T findMax() {
        synchronized (monitor) {
            requireNotEmpty();
            return entries[count == 1 ? 0 : 1].item;
        }
    }

    public T deleteMin() {
        synchronized (monitor) {
            return removeMin();
        }
    }

    public T deleteMax() {
        synchronized (monitor) {
            return removeMax();
        }
    }

    public T delete(Handle<T> handle) {
        synchronized (monitor) {
            return remove(checked(handle));
        }
    }

    public T replace(Handle<T> handle, T item) {
        synchronized (monitor) {
            if (item == null) {
                throw new NullPointerException("item");
            }
            Handle<T> valid = checked(handle);
            // Detachment and reinsertion are one atomic operation. No new
            // handle or additional array capacity is needed.
            T old = remove(valid);
            valid.item = item;
            insert(valid);
            return old;
        }
    }

    public int size() {
        synchronized (monitor) {
            return count;
        }
    }

    private void requireNotEmpty() {
        if (count == 0) {
            throw new IllegalStateException("IntervalHeap is empty");
        }
    }

    private Handle<T> checked(Handle<T> handle) {
        if (handle == null) {
            throw new NullPointerException("handle");
        }
        if (handle.owner != this || handle.index < 0
                || handle.index >= count || entries[handle.index] != handle) {
            throw new IllegalArgumentException("Foreign or invalidated handle");
        }
        return handle;
    }

    @SuppressWarnings("unchecked")
    private void ensureCapacity() {
        if (count < entries.length) {
            return;
        }
        int capacity = entries.length <= Integer.MAX_VALUE / 2
                ? entries.length * 2 : Integer.MAX_VALUE;
        if (capacity <= entries.length) {
            throw new OutOfMemoryError("IntervalHeap capacity exhausted");
        }
        Handle<T>[] grown = (Handle<T>[]) new Handle<?>[capacity];
        for (int i = 0; i < count; i++) {
            grown[i] = entries[i];
        }
        entries = grown;
    }

    private void put(int index, Handle<T> handle) {
        entries[index] = handle;
        handle.index = index;
    }

    private void swap(int left, int right) {
        Handle<T> saved = entries[left];
        put(left, entries[right]);
        put(right, saved);
    }

    private int highIndex(int node) {
        int high = node * 2 + 1;
        return high < count ? high : high - 1;
    }

    private void insert(Handle<T> handle) {
        ensureCapacity();
        int index = count++;
        put(index, handle);
        int node = index / 2;
        if ((index & 1) != 0) {
            if (order.compare(entries[index - 1].item, handle.item) > 0) {
                swap(index - 1, index);
                upMin(node);
            } else {
                upMax(node);
            }
        } else if (node != 0) {
            int parent = (node - 1) / 2;
            if (order.compare(handle.item, entries[parent * 2].item) < 0) {
                upMin(node);
            } else if (order.compare(handle.item,
                    entries[parent * 2 + 1].item) > 0) {
                upMax(node);
            }
        }
    }

    private void upMin(int node) {
        int index = node * 2;
        Handle<T> value = entries[index];
        while (node != 0) {
            int parent = (node - 1) / 2;
            int parentIndex = parent * 2;
            if (order.compare(value.item, entries[parentIndex].item) >= 0) {
                break;
            }
            put(index, entries[parentIndex]);
            node = parent;
            index = parentIndex;
        }
        put(index, value);
    }

    private void upMax(int node) {
        int index = highIndex(node);
        Handle<T> value = entries[index];
        while (node != 0) {
            int parent = (node - 1) / 2;
            int parentIndex = parent * 2 + 1;
            if (order.compare(value.item, entries[parentIndex].item) <= 0) {
                break;
            }
            put(index, entries[parentIndex]);
            node = parent;
            index = parentIndex;
        }
        put(index, value);
    }

    private T remove(Handle<T> handle) {
        boolean minimum = (handle.index & 1) == 0;
        int side = minimum ? 0 : 1;
        int node = handle.index / 2;
        // Moving ancestors down preserves all intervals and all heap edges
        // except those involving the root endpoint about to be removed.
        while (node != 0) {
            int parent = (node - 1) / 2;
            swap(node * 2 + side, parent * 2 + side);
            node = parent;
        }
        return minimum ? removeMin() : removeMax();
    }

    private T removeMin() {
        requireNotEmpty();
        Handle<T> removed = entries[0];
        T item = removed.item;
        Handle<T> last = entries[--count];
        entries[count] = null;
        removed.index = -1;
        removed.item = null;
        if (count != 0) {
            downMin(last);
        }
        return item;
    }

    private T removeMax() {
        requireNotEmpty();
        Handle<T> removed = entries[count == 1 ? 0 : 1];
        T item = removed.item;
        Handle<T> last = entries[--count];
        entries[count] = null;
        removed.index = -1;
        removed.item = null;
        if (count > 1) {
            downMax(last);
        }
        return item;
    }

    private void downMin(Handle<T> value) {
        int node = 0;
        int nodes = count / 2 + (count & 1);
        while (true) {
            int child = node * 2 + 1;
            if (child >= nodes) {
                break;
            }
            int right = child + 1;
            if (right < nodes && order.compare(entries[right * 2].item,
                    entries[child * 2].item) < 0) {
                child = right;
            }
            if (order.compare(value.item, entries[child * 2].item) <= 0) {
                break;
            }
            put(node * 2, entries[child * 2]);
            node = child;
        }
        int low = node * 2;
        put(low, value);
        if (low + 1 == count) {
            upMax(node);
        } else if (order.compare(value.item, entries[low + 1].item) > 0) {
            swap(low, low + 1);
            upMax(node);
        }
    }

    private void downMax(Handle<T> value) {
        int node = 0;
        int nodes = count / 2 + (count & 1);
        while (true) {
            int child = node * 2 + 1;
            if (child >= nodes) {
                break;
            }
            int right = child + 1;
            if (right < nodes && order.compare(entries[highIndex(right)].item,
                    entries[highIndex(child)].item) > 0) {
                child = right;
            }
            int childIndex = highIndex(child);
            if (order.compare(value.item, entries[childIndex].item) >= 0) {
                break;
            }
            put(highIndex(node), entries[childIndex]);
            node = child;
        }
        int high = highIndex(node);
        int low = node * 2;
        put(high, value);
        if (high == low) {
            upMin(node);
        } else if (order.compare(value.item, entries[low].item) < 0) {
            swap(low, high);
            upMin(node);
        }
    }
}

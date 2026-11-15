@IntervalHeap.ThreadSafe
public class IntervalHeap<T> {
    @interface ThreadSafe {}

    public interface Order<T> {
        int compare(T left, T right);
    }

    public static final class Handle<T> {
        private final Object owner;
        private Entry<T> entry;

        private Handle(Object owner, Entry<T> entry) {
            this.owner = owner;
            this.entry = entry;
        }
    }

    private static final class Entry<T> {
        private T item;
        private final Handle<T> handle;
        private int index;

        private Entry(T item, Handle<T> handle, int index) {
            this.item = item;
            this.handle = handle;
            this.index = index;
        }
    }

    private final Order<T> order;
    private final Object identity = new Object();
    private Entry<T>[] entries;
    private int count;

    @SuppressWarnings("unchecked")
    public IntervalHeap(Order<T> order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        this.order = order;
        this.entries = (Entry<T>[]) new Entry<?>[8];
    }

    public Handle<T> add(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (this) {
            ensureCapacity(count + 1);
            int index = count;
            Handle<T> handle = new Handle<T>(identity, null);
            Entry<T> entry = new Entry<T>(item, handle, index);
            handle.entry = entry;
            entries[index] = entry;
            count++;

            if ((index & 1) == 0) {
                if (index != 0) {
                    int node = index / 2;
                    int parent = (node - 1) / 2;
                    int parentLow = parent * 2;
                    int parentHigh = parentLow + 1;
                    if (compare(entries[index], entries[parentLow]) < 0) {
                        swap(index, parentLow);
                        bubbleLow(parent);
                    } else if (compare(entries[index], entries[parentHigh]) > 0) {
                        swap(index, parentHigh);
                        bubbleHigh(parent);
                    }
                }
            } else {
                int node = index / 2;
                int low = node * 2;
                if (compare(entries[index], entries[low]) < 0) {
                    swap(index, low);
                    bubbleLow(node);
                } else if (node != 0) {
                    int parentHigh = (((node - 1) / 2) * 2) + 1;
                    if (compare(entries[index], entries[parentHigh]) > 0) {
                        bubbleHigh(node);
                    }
                }
            }
            return handle;
        }
    }

    public T findMin() {
        synchronized (this) {
            requireNotEmpty();
            return entries[0].item;
        }
    }

    public T findMax() {
        synchronized (this) {
            requireNotEmpty();
            return entries[maxIndex()].item;
        }
    }

    public T deleteMin() {
        synchronized (this) {
            requireNotEmpty();
            Entry<T> removed = entries[0];
            removeAt(0);
            invalidate(removed);
            return removed.item;
        }
    }

    public T deleteMax() {
        synchronized (this) {
            requireNotEmpty();
            int index = maxIndex();
            Entry<T> removed = entries[index];
            removeAt(index);
            invalidate(removed);
            return removed.item;
        }
    }

    public T delete(Handle<T> handle) {
        if (handle == null) {
            throw new NullPointerException("handle");
        }
        synchronized (this) {
            Entry<T> entry = validate(handle);
            T oldItem = entry.item;
            removeAt(entry.index);
            invalidate(entry);
            return oldItem;
        }
    }

    public T replace(Handle<T> handle, T item) {
        if (handle == null || item == null) {
            throw new NullPointerException(handle == null ? "handle" : "item");
        }
        synchronized (this) {
            Entry<T> entry = validate(handle);
            T oldItem = entry.item;
            entry.item = item;
            repairAt(entry.index);
            return oldItem;
        }
    }

    public int size() {
        synchronized (this) {
            return count;
        }
    }

    private int compare(Entry<T> left, Entry<T> right) {
        return order.compare(left.item, right.item);
    }

    private Entry<T> validate(Handle<T> handle) {
        if (handle.owner != identity || handle.entry == null
                || handle.entry.handle != handle
                || handle.entry.index < 0
                || handle.entry.index >= count
                || entries[handle.entry.index] != handle.entry) {
            throw new IllegalArgumentException("foreign or invalid handle");
        }
        return handle.entry;
    }

    private void invalidate(Entry<T> entry) {
        entry.handle.entry = null;
        entry.index = -1;
    }

    private void requireNotEmpty() {
        if (count == 0) {
            throw new IllegalStateException("heap is empty");
        }
    }

    private int maxIndex() {
        if (count == 1) {
            return 0;
        }
        int rootHigh = 1;
        if ((count & 1) == 0) {
            int singleton = count - 1;
            return compare(entries[singleton], entries[rootHigh]) > 0
                    ? singleton : rootHigh;
        }
        int singleton = count - 1;
        return compare(entries[singleton], entries[rootHigh]) > 0
                ? singleton : rootHigh;
    }

    private void removeAt(int index) {
        int last = count - 1;
        if (index != last) {
            swap(index, last);
        }
        entries[last] = null;
        count--;
        if (index < count) {
            repairAt(index);
        }
    }

    private void repairAt(int index) {
        int node = index / 2;
        normalize(node);
        repairLow(node);
        repairHigh(node);
        normalize(node);
        repairLow(node);
        repairHigh(node);
    }

    private void normalize(int node) {
        int low = node * 2;
        int high = low + 1;
        if (high < count && compare(entries[low], entries[high]) > 0) {
            swap(low, high);
        }
    }

    private void repairLow(int node) {
        if (node * 2 >= count) {
            return;
        }
        normalize(node);

        if (node > 0) {
            int parent = (node - 1) / 2;
            if (compare(entries[node * 2], entries[parent * 2]) < 0) {
                while (node > 0) {
                    parent = (node - 1) / 2;
                    if (compare(entries[node * 2], entries[parent * 2]) >= 0) {
                        break;
                    }
                    swap(node * 2, parent * 2);
                    normalize(node);
                    node = parent;
                    normalize(node);
                }
                return;
            }
        }

        while (true) {
            int left = node * 2 + 1;
            if (left * 2 >= count) {
                break;
            }
            int right = left + 1;
            int child = left;
            if (right * 2 < count
                    && compare(entries[right * 2], entries[left * 2]) < 0) {
                child = right;
            }
            if (compare(entries[child * 2], entries[node * 2]) >= 0) {
                break;
            }
            swap(node * 2, child * 2);
            normalize(node);
            normalize(child);
            node = child;
        }
    }

    private void repairHigh(int node) {
        if (node * 2 + 1 >= count) {
            return;
        }
        normalize(node);

        if (node > 0) {
            int parent = (node - 1) / 2;
            if (compare(entries[node * 2 + 1], entries[parent * 2 + 1]) > 0) {
                while (node > 0) {
                    parent = (node - 1) / 2;
                    if (compare(entries[node * 2 + 1], entries[parent * 2 + 1]) <= 0) {
                        break;
                    }
                    swap(node * 2 + 1, parent * 2 + 1);
                    normalize(node);
                    node = parent;
                    normalize(node);
                }
                return;
            }
        }

        while (true) {
            int left = node * 2 + 1;
            if (left * 2 + 1 >= count) {
                break;
            }
            int right = left + 1;
            int child = left;
            if (right * 2 + 1 < count
                    && compare(entries[right * 2 + 1], entries[left * 2 + 1]) > 0) {
                child = right;
            }
            if (compare(entries[child * 2 + 1], entries[node * 2 + 1]) <= 0) {
                break;
            }
            swap(node * 2 + 1, child * 2 + 1);
            normalize(node);
            normalize(child);
            node = child;
        }
    }

    private void bubbleLow(int node) {
        while (node > 0) {
            int parent = (node - 1) / 2;
            if (compare(entries[node * 2], entries[parent * 2]) >= 0) {
                break;
            }
            swap(node * 2, parent * 2);
            node = parent;
        }
    }

    private void bubbleHigh(int node) {
        while (node > 0) {
            int parent = (node - 1) / 2;
            if (compare(entries[node * 2 + 1], entries[parent * 2 + 1]) <= 0) {
                break;
            }
            swap(node * 2 + 1, parent * 2 + 1);
            node = parent;
        }
    }

    private void swap(int first, int second) {
        Entry<T> temp = entries[first];
        entries[first] = entries[second];
        entries[second] = temp;
        entries[first].index = first;
        entries[second].index = second;
    }

    @SuppressWarnings("unchecked")
    private void ensureCapacity(int required) {
        if (required <= entries.length) {
            return;
        }
        int capacity = entries.length * 2;
        if (capacity < required) {
            capacity = required;
        }
        Entry<T>[] grown = (Entry<T>[]) new Entry<?>[capacity];
        for (int i = 0; i < count; i++) {
            grown[i] = entries[i];
        }
        entries = grown;
    }
}

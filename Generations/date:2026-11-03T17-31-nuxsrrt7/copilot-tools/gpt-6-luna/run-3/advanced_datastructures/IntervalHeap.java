@IntervalHeap.ThreadSafe
public final class IntervalHeap<T> {
    @interface ThreadSafe {}

    public interface Order<T> {
        int compare(T left, T right);
    }

    public static final class Handle<T> {
        private final Object owner;
        private final Entry<T> entry;

        private Handle(Object owner, Entry<T> entry) {
            this.owner = owner;
            this.entry = entry;
        }
    }

    private static final class Entry<T> {
        private T item;
        private int position;
        private boolean active;
        private Handle<T> handle;

        private Entry(T item) {
            this.item = item;
            this.active = true;
        }
    }

    private final Object monitor = new Object();
    private final Object owner = new Object();
    private final Order<T> order;
    private Entry<T>[] entries;
    private int size;

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
        synchronized (monitor) {
            ensureCapacity();
            Entry<T> entry = new Entry<T>(item);
            Handle<T> handle = new Handle<T>(owner, entry);
            entry.handle = handle;

            if ((size & 1) == 0) {
                entry.position = size;
                entries[size] = entry;
                size++;
                repairSingleton((size - 1) / 2);
            } else {
                int node = size / 2;
                Entry<T> singleton = entries[size - 1];
                if (order.compare(item, singleton.item) < 0) {
                    entry.position = size - 1;
                    singleton.position = size;
                    entries[size - 1] = entry;
                    entries[size] = singleton;
                    size++;
                    repairLow(node);
                } else {
                    entry.position = size;
                    entries[size] = entry;
                    size++;
                    repairHigh(node);
                }
            }
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
            return entries[size == 1 ? 0 : 1].item;
        }
    }

    public T deleteMin() {
        synchronized (monitor) {
            requireNotEmpty();
            return removeAt(0).item;
        }
    }

    public T deleteMax() {
        synchronized (monitor) {
            requireNotEmpty();
            return removeAt(size == 1 ? 0 : 1).item;
        }
    }

    public T delete(Handle<T> handle) {
        synchronized (monitor) {
            Entry<T> entry = validateHandle(handle);
            return removeAt(entry.position).item;
        }
    }

    public T replace(Handle<T> handle, T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (monitor) {
            Entry<T> entry = validateHandle(handle);
            T old = entry.item;
            entry.item = item;
            repairAt(entry.position);
            return old;
        }
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    private Entry<T> validateHandle(Handle<T> handle) {
        if (handle == null) {
            throw new NullPointerException("handle");
        }
        Entry<T> entry = handle.entry;
        if (handle.owner != owner || entry == null || !entry.active
                || entry.handle != handle) {
            throw new IllegalArgumentException("foreign or invalid handle");
        }
        return entry;
    }

    private void requireNotEmpty() {
        if (size == 0) {
            throw new IllegalStateException("heap is empty");
        }
    }

    private void ensureCapacity() {
        if (size < entries.length) {
            return;
        }
        int newCapacity = entries.length << 1;
        if (newCapacity <= entries.length) {
            throw new OutOfMemoryError("heap capacity exceeded");
        }
        @SuppressWarnings("unchecked")
        Entry<T>[] grown = (Entry<T>[]) new Entry<?>[newCapacity];
        for (int i = 0; i < size; i++) {
            grown[i] = entries[i];
        }
        entries = grown;
    }

    private Entry<T> removeAt(int position) {
        Entry<T> removed = entries[position];
        int last = size - 1;
        if (position != last) {
            Entry<T> moved = entries[last];
            entries[position] = moved;
            moved.position = position;
        }
        entries[last] = null;
        size--;
        removed.active = false;
        removed.position = -1;

        if (position < size) {
            int node = position / 2;
            if ((size & 1) != 0 && position == size - 1) {
                repairSingleton(node);
            } else {
                repairAt(position);
            }
        }
        return removed;
    }

    private void repairAt(int position) {
        int node = position / 2;
        if ((size & 1) != 0 && position == size - 1) {
            repairSingleton(node);
            return;
        }
        if ((position & 1) == 0) {
            if (order.compare(entries[position].item, entries[position + 1].item) > 0) {
                swap(position, position + 1);
                repairLow(node);
                repairHigh(node);
            } else {
                repairLow(node);
            }
        } else {
            if (order.compare(entries[position - 1].item, entries[position].item) > 0) {
                swap(position - 1, position);
                repairLow(node);
                repairHigh(node);
            } else {
                repairHigh(node);
            }
        }
    }

    private void repairSingleton(int node) {
        int position = node * 2;
        if (node == 0) {
            return;
        }
        int parent = (node - 1) / 2;
        int parentLow = parent * 2;
        int parentHigh = parentLow + 1;
        T item = entries[position].item;
        if (order.compare(item, entries[parentLow].item) < 0) {
            while (node > 0) {
                parent = (node - 1) / 2;
                parentLow = parent * 2;
                if (order.compare(entries[position].item, entries[parentLow].item) >= 0) {
                    break;
                }
                swap(position, parentLow);
                node = parent;
                position = node * 2;
            }
        } else if (order.compare(item, entries[parentHigh].item) > 0) {
            while (node > 0) {
                parent = (node - 1) / 2;
                parentHigh = parent * 2 + 1;
                if (order.compare(entries[position].item, entries[parentHigh].item) <= 0) {
                    break;
                }
                swap(position, parentHigh);
                node = parent;
                position = node * 2 + 1;
            }
        }
    }

    private void repairLow(int node) {
        int low = node * 2;
        int high = low + 1;
        if (high >= size) {
            repairSingleton(node);
            return;
        }
        if (order.compare(entries[low].item, entries[high].item) > 0) {
            swap(low, high);
            repairLow(node);
            repairHigh(node);
            return;
        }

        if (node > 0) {
            int parentLow = ((node - 1) / 2) * 2;
            if (order.compare(entries[low].item, entries[parentLow].item) < 0) {
                while (node > 0) {
                    parentLow = ((node - 1) / 2) * 2;
                    if (order.compare(entries[low].item, entries[parentLow].item) >= 0) {
                        break;
                    }
                    swap(low, parentLow);
                    node = (node - 1) / 2;
                    low = node * 2;
                    high = low + 1;
                    if (high < size && order.compare(entries[low].item, entries[high].item) > 0) {
                        swap(low, high);
                        repairHigh(node);
                    }
                }
                return;
            }
        }

        while (true) {
            int leftNode = node * 2 + 1;
            if (leftNode * 2 >= size) {
                return;
            }
            int child = leftNode;
            int rightNode = leftNode + 1;
            if (rightNode * 2 < size
                    && order.compare(entries[rightNode * 2].item, entries[leftNode * 2].item) < 0) {
                child = rightNode;
            }
            int childLow = child * 2;
            if (order.compare(entries[childLow].item, entries[low].item) >= 0) {
                return;
            }
            swap(low, childLow);
            node = child;
            low = node * 2;
            high = low + 1;
            if (high < size && order.compare(entries[low].item, entries[high].item) > 0) {
                swap(low, high);
            }
        }
    }

    private void repairHigh(int node) {
        int low = node * 2;
        int high = low + 1;
        if (high >= size) {
            repairSingleton(node);
            return;
        }
        if (order.compare(entries[low].item, entries[high].item) > 0) {
            swap(low, high);
            repairLow(node);
            repairHigh(node);
            return;
        }

        if (node > 0) {
            int parentHigh = ((node - 1) / 2) * 2 + 1;
            if (order.compare(entries[high].item, entries[parentHigh].item) > 0) {
                while (node > 0) {
                    parentHigh = ((node - 1) / 2) * 2 + 1;
                    if (order.compare(entries[high].item, entries[parentHigh].item) <= 0) {
                        break;
                    }
                    swap(high, parentHigh);
                    node = (node - 1) / 2;
                    low = node * 2;
                    high = low + 1;
                    if (high < size && order.compare(entries[low].item, entries[high].item) > 0) {
                        swap(low, high);
                        repairLow(node);
                    }
                }
                return;
            }
        }

        while (true) {
            int leftNode = node * 2 + 1;
            if (leftNode * 2 >= size) {
                return;
            }
            int child = leftNode;
            int rightNode = leftNode + 1;
            int leftHigh = leftNode * 2 + 1 < size ? leftNode * 2 + 1 : leftNode * 2;
            if (rightNode * 2 < size) {
                int rightHigh = rightNode * 2 + 1 < size ? rightNode * 2 + 1 : rightNode * 2;
                if (order.compare(entries[rightHigh].item, entries[leftHigh].item) > 0) {
                    child = rightNode;
                }
            }
            int childHigh = child * 2 + 1 < size ? child * 2 + 1 : child * 2;
            if (order.compare(entries[childHigh].item, entries[high].item) <= 0) {
                return;
            }
            swap(high, childHigh);
            node = child;
            low = node * 2;
            if (low + 1 >= size) {
                return;
            }
            high = low + 1;
            if (order.compare(entries[low].item, entries[high].item) > 0) {
                swap(low, high);
            }
        }
    }

    private void swap(int first, int second) {
        Entry<T> temporary = entries[first];
        entries[first] = entries[second];
        entries[second] = temporary;
        entries[first].position = first;
        entries[second].position = second;
    }
}

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
        private int index;
        private boolean active;
        private Handle<T> handle;

        private Entry(T item, int index) {
            this.item = item;
            this.index = index;
            this.active = true;
        }
    }

    private final Object lock = new Object();
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
        this.entries = (Entry<T>[]) new Entry[8];
    }

    public Handle<T> add(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            ensureCapacity();
            Entry<T> entry = new Entry<T>(item, size);
            Handle<T> handle = new Handle<T>(owner, entry);
            entry.handle = handle;

            int oldSize = size;
            entries[size++] = entry;

            if (oldSize == 0) {
                return handle;
            }

            if ((oldSize & 1) == 0) {
                int node = oldSize / 2;
                int parent = (node - 1) / 2;
                int parentLow = lowIndex(parent);
                int parentHigh = highIndex(parent);

                if (compare(entries[entry.index].item, entries[parentLow].item) < 0) {
                    swap(entry.index, parentLow);
                    bubbleUpLow(parent);
                } else if (compare(entries[entry.index].item, entries[parentHigh].item) > 0) {
                    swap(entry.index, parentHigh);
                    bubbleUpHigh(parent);
                }
            } else {
                int singletonIndex = oldSize - 1;
                Entry<T> singleton = entries[singletonIndex];
                if (compare(item, singleton.item) < 0) {
                    entries[singletonIndex + 1] = singleton;
                    singleton.index = singletonIndex + 1;
                    entries[singletonIndex] = entry;
                    entry.index = singletonIndex;
                }

                int node = oldSize / 2;
                int parent = (node - 1) / 2;
                if (compare(entries[lowIndex(node)].item, entries[lowIndex(parent)].item) < 0) {
                    swap(lowIndex(node), lowIndex(parent));
                    bubbleUpLow(parent);
                } else if (compare(entries[highIndex(node)].item, entries[highIndex(parent)].item) > 0) {
                    swap(highIndex(node), highIndex(parent));
                    bubbleUpHigh(parent);
                }
            }

            return handle;
        }
    }

    public T findMin() {
        synchronized (lock) {
            ensureNotEmpty();
            return entries[0].item;
        }
    }

    public T findMax() {
        synchronized (lock) {
            ensureNotEmpty();
            return entries[size == 1 ? 0 : 1].item;
        }
    }

    public T deleteMin() {
        synchronized (lock) {
            ensureNotEmpty();
            return removeAt(0);
        }
    }

    public T deleteMax() {
        synchronized (lock) {
            ensureNotEmpty();
            return removeAt(size == 1 ? 0 : 1);
        }
    }

    public T delete(Handle<T> handle) {
        synchronized (lock) {
            Entry<T> entry = validateHandle(handle);
            return removeAt(entry.index);
        }
    }

    public T replace(Handle<T> handle, T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            Entry<T> entry = validateHandle(handle);
            T oldItem = entry.item;
            entry.item = item;
            repairNode(entry.index / 2);
            return oldItem;
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    private int compare(T left, T right) {
        return order.compare(left, right);
    }

    private void ensureNotEmpty() {
        if (size == 0) {
            throw new IllegalStateException("interval heap is empty");
        }
    }

    private Entry<T> validateHandle(Handle<T> handle) {
        if (handle == null) {
            throw new NullPointerException("handle");
        }
        Entry<T> entry = handle.entry;
        if (handle.owner != owner || entry == null || !entry.active || entry.handle != handle) {
            throw new IllegalArgumentException("foreign or invalidated handle");
        }
        return entry;
    }

    private T removeAt(int index) {
        Entry<T> removed = entries[index];
        T item = removed.item;
        int lastIndex = size - 1;

        if (index != lastIndex) {
            Entry<T> moved = entries[lastIndex];
            entries[index] = moved;
            moved.index = index;
        }

        entries[lastIndex] = null;
        size--;

        removed.active = false;
        removed.index = -1;

        if (index < size) {
            repairNode(index / 2);
        }
        return item;
    }

    private void ensureCapacity() {
        if (size < entries.length) {
            return;
        }
        int newCapacity = entries.length <= Integer.MAX_VALUE / 2
                ? entries.length * 2
                : Integer.MAX_VALUE;
        if (newCapacity <= entries.length) {
            throw new OutOfMemoryError("interval heap is too large");
        }

        @SuppressWarnings("unchecked")
        Entry<T>[] grown = (Entry<T>[]) new Entry[newCapacity];
        for (int i = 0; i < size; i++) {
            grown[i] = entries[i];
        }
        entries = grown;
    }

    private int lowIndex(int node) {
        return node * 2;
    }

    private int highIndex(int node) {
        int index = node * 2 + 1;
        return index < size ? index : node * 2;
    }

    private void swap(int first, int second) {
        if (first == second) {
            return;
        }
        Entry<T> temporary = entries[first];
        entries[first] = entries[second];
        entries[second] = temporary;
        entries[first].index = first;
        entries[second].index = second;
    }

    private void bubbleUpLow(int node) {
        while (node > 0) {
            int parent = (node - 1) / 2;
            if (compare(entries[lowIndex(node)].item, entries[lowIndex(parent)].item) >= 0) {
                return;
            }
            swap(lowIndex(node), lowIndex(parent));
            node = parent;
        }
    }

    private void bubbleUpHigh(int node) {
        while (node > 0) {
            int parent = (node - 1) / 2;
            if (compare(entries[highIndex(node)].item, entries[highIndex(parent)].item) <= 0) {
                return;
            }
            swap(highIndex(node), highIndex(parent));
            node = parent;
        }
    }

    private void repairNode(int node) {
        int nodeCount = (size + 1) / 2;

        while (true) {
            int low = lowIndex(node);
            int high = highIndex(node);

            if (low != high && compare(entries[low].item, entries[high].item) > 0) {
                swap(low, high);
            }

            if (node > 0) {
                int parent = (node - 1) / 2;
                int parentLow = lowIndex(parent);
                int parentHigh = highIndex(parent);

                if (compare(entries[lowIndex(node)].item, entries[parentLow].item) < 0) {
                    swap(lowIndex(node), parentLow);
                    node = parent;
                    continue;
                }
                if (compare(entries[highIndex(node)].item, entries[parentHigh].item) > 0) {
                    swap(highIndex(node), parentHigh);
                    node = parent;
                    continue;
                }
            }

            int leftChild = node * 2 + 1;
            int rightChild = leftChild + 1;

            if (leftChild < nodeCount) {
                int minChild = leftChild;
                if (rightChild < nodeCount
                        && compare(entries[lowIndex(rightChild)].item,
                                   entries[lowIndex(leftChild)].item) < 0) {
                    minChild = rightChild;
                }

                if (compare(entries[lowIndex(node)].item,
                            entries[lowIndex(minChild)].item) > 0) {
                    swap(lowIndex(node), lowIndex(minChild));
                    node = minChild;
                    continue;
                }

                int maxChild = leftChild;
                if (rightChild < nodeCount
                        && compare(entries[highIndex(rightChild)].item,
                                   entries[highIndex(leftChild)].item) > 0) {
                    maxChild = rightChild;
                }

                if (compare(entries[highIndex(node)].item,
                            entries[highIndex(maxChild)].item) < 0) {
                    swap(highIndex(node), highIndex(maxChild));
                    node = maxChild;
                    continue;
                }
            }

            return;
        }
    }
}

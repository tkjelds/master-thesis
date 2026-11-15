@IntervalHeap.ThreadSafe
public final class IntervalHeap<T> {
    @interface ThreadSafe {}

    public interface Order<E> {
        int compare(E left, E right);
    }

    public static final class Handle<E> {
        private final Object owner;
        private Entry<E> entry;

        private Handle(Object owner, Entry<E> entry) {
            this.owner = owner;
            this.entry = entry;
        }
    }

    private static final class Entry<E> {
        private E item;
        private final Handle<E> handle;
        private int index;

        private Entry(E item, Handle<E> handle, int index) {
            this.item = item;
            this.handle = handle;
            this.index = index;
        }
    }

    private final Order<? super T> order;
    private Entry<T>[] entries;
    private int size;

    public IntervalHeap(Order<? super T> order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        this.order = order;
        this.entries = new Entry[8];
    }

    public Handle<T> add(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (this) {
            ensureCapacity(size + 1);
            Handle<T> handle = new Handle<T>(this, null);
            Entry<T> entry = new Entry<T>(item, handle, size);
            handle.entry = entry;
            entries[size] = entry;
            size++;

            int node = entry.index / 2;
            if ((entry.index & 1) == 1) {
                normalize(node);
                Entry<T> low = entries[node * 2];
                Entry<T> high = entries[node * 2 + 1];
                int parent = (node - 1) / 2;
                if (node > 0 && compare(low.item, entries[parent * 2].item) < 0) {
                    repairEntry(low);
                } else if (node > 0 && compare(high.item, entries[parent * 2 + 1].item) > 0) {
                    repairEntry(high);
                }
            } else if (node > 0) {
                Entry<T> parentLow = entries[((node - 1) / 2) * 2];
                Entry<T> parentHigh = entries[((node - 1) / 2) * 2 + 1];
                if (compare(item, parentLow.item) < 0) {
                    repairEntry(entry);
                } else if (compare(item, parentHigh.item) > 0) {
                    repairEntry(entry);
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
            return entries[size > 1 ? 1 : 0].item;
        }
    }

    public T deleteMin() {
        synchronized (this) {
            requireNotEmpty();
            return removeAt(0);
        }
    }

    public T deleteMax() {
        synchronized (this) {
            requireNotEmpty();
            return removeAt(size > 1 ? 1 : 0);
        }
    }

    public T delete(Handle<T> handle) {
        if (handle == null) {
            throw new NullPointerException("handle");
        }
        synchronized (this) {
            Entry<T> entry = requireValid(handle);
            return removeAt(entry.index);
        }
    }

    public T replace(Handle<T> handle, T item) {
        if (handle == null) {
            throw new NullPointerException("handle");
        }
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (this) {
            Entry<T> entry = requireValid(handle);
            T oldItem = entry.item;
            entry.item = item;
            repairEntry(entry);
            return oldItem;
        }
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    private void requireNotEmpty() {
        if (size == 0) {
            throw new IllegalStateException("IntervalHeap is empty");
        }
    }

    private Entry<T> requireValid(Handle<T> handle) {
        if (handle.owner != this || handle.entry == null) {
            throw new IllegalArgumentException("Handle is foreign or invalidated");
        }
        return handle.entry;
    }

    private int compare(T left, T right) {
        return order.compare(left, right);
    }

    private void ensureCapacity(int required) {
        if (required <= entries.length) {
            return;
        }
        int capacity = entries.length * 2;
        if (capacity < required) {
            capacity = required;
        }
        Entry<T>[] grown = new Entry[capacity];
        for (int i = 0; i < size; i++) {
            grown[i] = entries[i];
        }
        entries = grown;
    }

    private T removeAt(int index) {
        Entry<T> removed = entries[index];
        int last = size - 1;
        if (index != last) {
            Entry<T> moved = entries[last];
            entries[index] = moved;
            moved.index = index;
        }
        entries[last] = null;
        size--;
        removed.handle.entry = null;

        if (index < size) {
            repairEntry(entries[index]);
        }
        return removed.item;
    }

    private void repairEntry(Entry<T> entry) {
        while (true) {
            int node = entry.index / 2;
            normalize(node);

            int lowIndex = node * 2;
            int highIndex = lowIndex + 1;
            boolean hasHigh = highIndex < size;
            int parentNode = (node - 1) / 2;

            if (node > 0) {
                Entry<T> parentLow = entries[parentNode * 2];
                Entry<T> parentHigh = entries[parentNode * 2 + 1];
                if (compare(entry.item, parentLow.item) < 0) {
                    swap(entry.index, parentLow.index);
                    normalize(node);
                    continue;
                }
                if (compare(entry.item, parentHigh.item) > 0) {
                    swap(entry.index, parentHigh.index);
                    normalize(node);
                    continue;
                }
            }

            boolean isLow = entry.index == lowIndex;
            if (!hasHigh) {
                int child = firstChild(node);
                if (child >= nodeCount()) {
                    return;
                }
                Entry<T> childLow = entries[child * 2];
                if (compare(entry.item, childLow.item) > 0) {
                    swap(entry.index, childLow.index);
                    normalize(child);
                    continue;
                }
                int maxChild = child;
                int rightChild = child + 1;
                if (rightChild < nodeCount()
                        && compare(entries[rightChild * 2 + (rightChild * 2 + 1 < size ? 1 : 0)].item,
                                   entries[maxChild * 2 + (maxChild * 2 + 1 < size ? 1 : 0)].item) > 0) {
                    maxChild = rightChild;
                }
                int maxIndex = maxChild * 2 + (maxChild * 2 + 1 < size ? 1 : 0);
                if (compare(entry.item, entries[maxIndex].item) < 0) {
                    swap(entry.index, maxIndex);
                    normalize(maxChild);
                    continue;
                }
                return;
            }

            if (isLow) {
                int child = firstChild(node);
                if (child >= nodeCount()) {
                    return;
                }
                int minChild = child;
                int rightChild = child + 1;
                if (rightChild < nodeCount()
                        && compare(entries[rightChild * 2].item, entries[minChild * 2].item) < 0) {
                    minChild = rightChild;
                }
                if (compare(entry.item, entries[minChild * 2].item) > 0) {
                    swap(entry.index, entries[minChild * 2].index);
                    normalize(minChild);
                    continue;
                }
            } else {
                int child = firstChild(node);
                if (child >= nodeCount()) {
                    return;
                }
                int maxChild = child;
                int rightChild = child + 1;
                if (rightChild < nodeCount()) {
                    int rightHigh = rightChild * 2 + (rightChild * 2 + 1 < size ? 1 : 0);
                    int leftHigh = maxChild * 2 + (maxChild * 2 + 1 < size ? 1 : 0);
                    if (compare(entries[rightHigh].item, entries[leftHigh].item) > 0) {
                        maxChild = rightChild;
                    }
                }
                int maxIndex = maxChild * 2 + (maxChild * 2 + 1 < size ? 1 : 0);
                if (compare(entry.item, entries[maxIndex].item) < 0) {
                    swap(entry.index, maxIndex);
                    normalize(maxChild);
                    continue;
                }
            }
            return;
        }
    }

    private int nodeCount() {
        return (size + 1) / 2;
    }

    private int firstChild(int node) {
        return node * 2 + 1;
    }

    private void normalize(int node) {
        int low = node * 2;
        int high = low + 1;
        if (high < size && compare(entries[low].item, entries[high].item) > 0) {
            swap(low, high);
        }
    }

    private void swap(int left, int right) {
        if (left == right) {
            return;
        }
        Entry<T> temporary = entries[left];
        entries[left] = entries[right];
        entries[right] = temporary;
        entries[left].index = left;
        entries[right].index = right;
    }
}

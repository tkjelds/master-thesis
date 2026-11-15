@IntervalHeap.ThreadSafe
public final class IntervalHeap<T> {
    @interface ThreadSafe {}

    public interface Order<E> {
        int compare(E left, E right);
    }

    public static final class Handle<E> {
        private final IntervalHeap<E> owner;
        private final Entry<E> entry;

        private Handle(IntervalHeap<E> owner, Entry<E> entry) {
            this.owner = owner;
            this.entry = entry;
        }
    }

    private static final class Entry<E> {
        private E item;
        private int index;
        private boolean active;
        private Handle<E> handle;

        private Entry(E item) {
            this.item = item;
            this.active = true;
        }
    }

    private final Order<T> order;
    private final Object monitor = new Object();
    private Object[] elements = new Object[8];
    private int size;

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

        synchronized (monitor) {
            if (size == Integer.MAX_VALUE) {
                throw new IllegalStateException("Heap is full");
            }
            ensureCapacity(size + 1);

            Entry<T> entry = new Entry<T>(item);
            Handle<T> handle = new Handle<T>(this, entry);
            entry.handle = handle;

            int oldSize = size;
            elements[size] = entry;
            entry.index = size;
            size++;

            if (oldSize == 0) {
                return handle;
            }

            if ((oldSize & 1) == 0) {
                int node = oldSize >>> 1;
                int parent = (node - 1) >>> 1;
                Entry<T> parentLow = entryAt(parent << 1);
                Entry<T> parentHigh = entryAt((parent << 1) + 1);

                if (order.compare(item, parentLow.item) < 0) {
                    siftUpLow(entry);
                } else if (order.compare(item, parentHigh.item) > 0) {
                    siftUpHigh(entry);
                }
            } else {
                int lowIndex = oldSize - 1;
                Entry<T> oldSingleton = entryAt(lowIndex);
                if (order.compare(item, oldSingleton.item) < 0) {
                    swap(lowIndex, oldSize);
                    siftUpLow(entry);
                } else {
                    siftUpHigh(entry);
                }
            }

            return handle;
        }
    }

    public T findMin() {
        synchronized (monitor) {
            requireNonEmpty();
            return entryAt(0).item;
        }
    }

    public T findMax() {
        synchronized (monitor) {
            requireNonEmpty();
            return entryAt(size == 1 ? 0 : 1).item;
        }
    }

    public T deleteMin() {
        synchronized (monitor) {
            requireNonEmpty();
            return removeAt(0);
        }
    }

    public T deleteMax() {
        synchronized (monitor) {
            requireNonEmpty();
            return removeAt(size == 1 ? 0 : 1);
        }
    }

    public T delete(Handle<T> handle) {
        if (handle == null) {
            throw new NullPointerException("handle");
        }

        synchronized (monitor) {
            Entry<T> entry = validate(handle);
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

        synchronized (monitor) {
            Entry<T> entry = validate(handle);
            T oldItem = entry.item;
            entry.item = item;
            repairAtNode(entry.index >>> 1);
            return oldItem;
        }
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    private void requireNonEmpty() {
        if (size == 0) {
            throw new IllegalStateException("Heap is empty");
        }
    }

    private Entry<T> validate(Handle<T> handle) {
        if (handle.owner != this || handle.entry == null || !handle.entry.active) {
            throw new IllegalArgumentException("Foreign or invalidated handle");
        }
        return handle.entry;
    }

    private T removeAt(int index) {
        Entry<T> removed = entryAt(index);
        T item = removed.item;
        int lastIndex = size - 1;

        if (index != lastIndex) {
            Entry<T> moved = entryAt(lastIndex);
            elements[index] = moved;
            moved.index = index;
        }
        elements[lastIndex] = null;
        size--;

        removed.active = false;
        removed.index = -1;
        removed.item = null;

        if (index < size) {
            repairAtNode(index >>> 1);
        }
        return item;
    }

    private void repairAtNode(int node) {
        int lowIndex = node << 1;
        if (lowIndex >= size) {
            return;
        }

        int highIndex = lowIndex + 1;
        if (highIndex >= size) {
            Entry<T> singleton = entryAt(lowIndex);
            if (node > 0) {
                int parent = (node - 1) >>> 1;
                Entry<T> parentLow = entryAt(parent << 1);
                Entry<T> parentHigh = entryAt((parent << 1) + 1);
                if (order.compare(singleton.item, parentLow.item) < 0) {
                    siftUpLow(singleton);
                } else if (order.compare(singleton.item, parentHigh.item) > 0) {
                    siftUpHigh(singleton);
                }
            }
            return;
        }

        if (order.compare(entryAt(lowIndex).item, entryAt(highIndex).item) > 0) {
            swap(lowIndex, highIndex);
        }

        repairLowAtNode(node);
        repairHighAtNode(node);
    }

    private void repairLowAtNode(int node) {
        int lowIndex = node << 1;
        if (node > 0) {
            int parentLowIndex = (((node - 1) >>> 1) << 1);
            if (order.compare(entryAt(lowIndex).item, entryAt(parentLowIndex).item) < 0) {
                siftUpLow(entryAt(lowIndex));
                return;
            }
        }
        siftDownLow(entryAt(lowIndex));
    }

    private void repairHighAtNode(int node) {
        int highIndex = (node << 1) + 1;
        if (highIndex >= size) {
            return;
        }
        if (node > 0) {
            int parentHighIndex = ((((node - 1) >>> 1) << 1) + 1);
            if (order.compare(entryAt(highIndex).item, entryAt(parentHighIndex).item) > 0) {
                siftUpHigh(entryAt(highIndex));
                return;
            }
        }
        siftDownHigh(entryAt(highIndex));
    }

    private void siftUpLow(Entry<T> entry) {
        while (true) {
            int node = entry.index >>> 1;
            if (node == 0) {
                return;
            }
            int parentLowIndex = (((node - 1) >>> 1) << 1);
            if (order.compare(entry.item, entryAt(parentLowIndex).item) >= 0) {
                return;
            }
            swap(entry.index, parentLowIndex);
        }
    }

    private void siftUpHigh(Entry<T> entry) {
        while (true) {
            int node = entry.index >>> 1;
            if (node == 0) {
                return;
            }
            int parentHighIndex = ((((node - 1) >>> 1) << 1) + 1);
            if (order.compare(entry.item, entryAt(parentHighIndex).item) <= 0) {
                return;
            }
            swap(entry.index, parentHighIndex);
        }
    }

    private void siftDownLow(Entry<T> entry) {
        while (true) {
            int node = entry.index >>> 1;
            int firstChild = (node << 1) + 1;
            int nodeCount = size / 2 + size % 2;
            if (firstChild >= nodeCount) {
                return;
            }

            int child = firstChild;
            int secondChild = firstChild + 1;
            if (secondChild < nodeCount
                    && order.compare(entryAt(secondChild << 1).item,
                                     entryAt(child << 1).item) < 0) {
                child = secondChild;
            }

            int childLowIndex = child << 1;
            if (order.compare(entry.item, entryAt(childLowIndex).item) <= 0) {
                return;
            }

            int childHighIndex = childLowIndex + 1;
            if (childHighIndex < size
                    && order.compare(entry.item, entryAt(childHighIndex).item) > 0) {
                swap(entry.index, childLowIndex);
                swap(entry.index, childHighIndex);
                repairAtNode(child);
                return;
            }

            swap(entry.index, childLowIndex);
        }
    }

    private void siftDownHigh(Entry<T> entry) {
        while (true) {
            int node = entry.index >>> 1;
            int firstChild = (node << 1) + 1;
            int nodeCount = size / 2 + size % 2;
            if (firstChild >= nodeCount) {
                return;
            }

            int child = firstChild;
            int secondChild = firstChild + 1;
            int firstHighIndex = highIndexForNode(firstChild);
            if (secondChild < nodeCount
                    && order.compare(entryAt(highIndexForNode(secondChild)).item,
                                     entryAt(firstHighIndex).item) > 0) {
                child = secondChild;
            }

            int childHighIndex = highIndexForNode(child);
            if (order.compare(entry.item, entryAt(childHighIndex).item) >= 0) {
                return;
            }

            int childLowIndex = child << 1;
            if (order.compare(entry.item, entryAt(childLowIndex).item) < 0) {
                swap(entry.index, childHighIndex);
                swap(entry.index, childLowIndex);
                repairAtNode(child);
                return;
            }

            swap(entry.index, childHighIndex);
        }
    }

    private int highIndexForNode(int node) {
        int highIndex = (node << 1) + 1;
        return highIndex < size ? highIndex : highIndex - 1;
    }

    private void swap(int firstIndex, int secondIndex) {
        if (firstIndex == secondIndex) {
            return;
        }
        Entry<T> first = entryAt(firstIndex);
        Entry<T> second = entryAt(secondIndex);
        elements[firstIndex] = second;
        elements[secondIndex] = first;
        first.index = secondIndex;
        second.index = firstIndex;
    }

    @SuppressWarnings("unchecked")
    private Entry<T> entryAt(int index) {
        return (Entry<T>) elements[index];
    }

    private void ensureCapacity(int required) {
        if (required <= elements.length) {
            return;
        }

        int capacity = elements.length;
        int newCapacity = capacity <= Integer.MAX_VALUE / 2
                ? capacity * 2
                : Integer.MAX_VALUE;
        if (newCapacity < required) {
            newCapacity = required;
        }

        Object[] replacement = new Object[newCapacity];
        for (int i = 0; i < size; i++) {
            replacement[i] = elements[i];
        }
        elements = replacement;
    }
}

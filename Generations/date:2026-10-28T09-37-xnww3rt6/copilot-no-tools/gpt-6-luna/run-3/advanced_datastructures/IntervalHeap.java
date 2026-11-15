@IntervalHeap.ThreadSafe
public class IntervalHeap<T> {
    @interface ThreadSafe {}

    public interface Order<E> {
        int compare(E left, E right);
    }

    public static final class Handle<E> {
        private final IntervalHeap<?> owner;
        private final Entry<E> entry;

        private Handle(IntervalHeap<?> owner, Entry<E> entry) {
            this.owner = owner;
            this.entry = entry;
        }
    }

    private static final class Entry<E> {
        private E item;
        private int slot;
        private Handle<E> handle;

        private Entry(E item) {
            this.item = item;
            this.slot = -1;
        }
    }

    private final Order<T> order;
    private final Object monitor = new Object();
    private Object[] entries = new Object[8];
    private int size;

    public IntervalHeap(Order<T> order) {
        if (order == null) {
            throw new NullPointerException();
        }
        this.order = order;
    }

    public Handle<T> add(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (monitor) {
            Entry<T> entry = new Entry<T>(item);
            Handle<T> handle = new Handle<T>(this, entry);
            entry.handle = handle;
            insertEntry(entry);
            return handle;
        }
    }

    public T findMin() {
        synchronized (monitor) {
            if (size == 0) {
                throw new IllegalStateException();
            }
            return entryAt(0).item;
        }
    }

    public T findMax() {
        synchronized (monitor) {
            if (size == 0) {
                throw new IllegalStateException();
            }
            return entryAt(size == 1 ? 0 : 1).item;
        }
    }

    public T deleteMin() {
        synchronized (monitor) {
            if (size == 0) {
                throw new IllegalStateException();
            }
            Entry<T> entry = entryAt(0);
            T item = entry.item;
            detachEntry(entry);
            entry.handle = null;
            return item;
        }
    }

    public T deleteMax() {
        synchronized (monitor) {
            if (size == 0) {
                throw new IllegalStateException();
            }
            Entry<T> entry = entryAt(size == 1 ? 0 : 1);
            T item = entry.item;
            detachEntry(entry);
            entry.handle = null;
            return item;
        }
    }

    public T delete(Handle<T> handle) {
        if (handle == null) {
            throw new NullPointerException();
        }
        synchronized (monitor) {
            Entry<T> entry = validateHandle(handle);
            T item = entry.item;
            detachEntry(entry);
            entry.handle = null;
            return item;
        }
    }

    public T replace(Handle<T> handle, T item) {
        if (handle == null || item == null) {
            throw new NullPointerException();
        }
        synchronized (monitor) {
            Entry<T> entry = validateHandle(handle);
            T oldItem = entry.item;
            detachEntry(entry);
            entry.item = item;
            insertEntry(entry);
            return oldItem;
        }
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    private Entry<T> validateHandle(Handle<T> handle) {
        if (handle.owner != this) {
            throw new IllegalArgumentException();
        }
        Entry<T> entry = handle.entry;
        if (entry.handle != handle || entry.slot < 0
                || entry.slot >= size || entryAt(entry.slot) != entry) {
            throw new IllegalArgumentException();
        }
        return entry;
    }

    private void insertEntry(Entry<T> entry) {
        ensureCapacity(size + 1);

        if ((size & 1) == 0) {
            int slot = size;
            entries[slot] = entry;
            entry.slot = slot;
            size++;

            if (slot == 0) {
                return;
            }

            int node = slot / 2;
            int parent = (node - 1) / 2;
            int parentLow = parent * 2;
            int parentHigh = parentLow + 1;

            if (order.compare(entry.item, entryAt(parentLow).item) < 0) {
                swapSlots(slot, parentLow);
                repairLow(parent);
            } else if (order.compare(entry.item, entryAt(parentHigh).item) > 0) {
                swapSlots(slot, parentHigh);
                repairHigh(parent);
            }
            return;
        }

        int node = size / 2;
        int low = node * 2;
        int high = size;
        entries[high] = entry;
        entry.slot = high;
        size++;

        if (order.compare(entry.item, entryAt(low).item) < 0) {
            swapSlots(low, high);
            repairLow(node);
        } else {
            repairHigh(node);
        }
    }

    private void detachEntry(Entry<T> entry) {
        int slot = entry.slot;
        int last = size - 1;

        if (slot == last) {
            entries[last] = null;
            size--;
            entry.slot = -1;
            return;
        }

        Entry<T> replacement = entryAt(last);
        entries[slot] = replacement;
        entries[last] = null;
        size--;
        replacement.slot = slot;
        entry.slot = -1;

        int node = slot / 2;
        if ((node * 2 + 1) >= size) {
            repairSingleton(node);
        } else if ((slot & 1) == 0) {
            repairLow(node);
        } else {
            repairHigh(node);
        }
    }

    private void repairSingleton(int node) {
        if (node == 0) {
            return;
        }

        int slot = node * 2;
        int parent = (node - 1) / 2;
        int parentLow = parent * 2;
        int parentHigh = parentLow + 1;
        int comparison = order.compare(entryAt(slot).item, entryAt(parentLow).item);

        if (comparison < 0) {
            swapSlots(slot, parentLow);
            repairLow(parent);
        } else if (order.compare(entryAt(slot).item, entryAt(parentHigh).item) > 0) {
            swapSlots(slot, parentHigh);
            repairHigh(parent);
        }
    }

    private void repairLow(int node) {
        if (!hasHigh(node)) {
            repairSingleton(node);
            return;
        }

        int low = node * 2;
        int high = low + 1;
        if (order.compare(entryAt(low).item, entryAt(high).item) > 0) {
            swapSlots(low, high);
            repairHigh(node);
            return;
        }

        if (node > 0) {
            int parent = (node - 1) / 2;
            int parentLow = parent * 2;
            if (order.compare(entryAt(low).item, entryAt(parentLow).item) < 0) {
                while (node > 0) {
                    parent = (node - 1) / 2;
                    parentLow = parent * 2;
                    if (order.compare(entryAt(node * 2).item,
                            entryAt(parentLow).item) >= 0) {
                        break;
                    }
                    swapSlots(node * 2, parentLow);
                    node = parent;
                }
                return;
            }
        }

        while (true) {
            int left = node * 2 + 1;
            if (left * 2 >= size) {
                return;
            }
            int right = left + 1;
            int child = left;
            if (right * 2 < size
                    && order.compare(entryAt(right * 2).item,
                            entryAt(left * 2).item) < 0) {
                child = right;
            }

            int nodeLow = node * 2;
            int childLow = child * 2;
            if (order.compare(entryAt(childLow).item, entryAt(nodeLow).item) >= 0) {
                return;
            }

            swapSlots(nodeLow, childLow);
            if (hasHigh(child)
                    && order.compare(entryAt(child * 2).item,
                            entryAt(child * 2 + 1).item) > 0) {
                swapSlots(child * 2, child * 2 + 1);
            }
            node = child;
        }
    }

    private void repairHigh(int node) {
        if (!hasHigh(node)) {
            repairSingleton(node);
            return;
        }

        int low = node * 2;
        int high = low + 1;
        if (order.compare(entryAt(high).item, entryAt(low).item) < 0) {
            swapSlots(low, high);
            repairLow(node);
            return;
        }

        if (node > 0) {
            int parent = (node - 1) / 2;
            int parentHigh = parent * 2 + 1;
            if (order.compare(entryAt(high).item, entryAt(parentHigh).item) > 0) {
                while (node > 0) {
                    parent = (node - 1) / 2;
                    parentHigh = parent * 2 + 1;
                    if (order.compare(entryAt(node * 2 + 1).item,
                            entryAt(parentHigh).item) <= 0) {
                        break;
                    }
                    swapSlots(node * 2 + 1, parentHigh);
                    node = parent;
                }
                return;
            }
        }

        while (true) {
            int left = node * 2 + 1;
            if (left * 2 >= size) {
                return;
            }
            int right = left + 1;
            int child = left;
            if (right * 2 < size
                    && order.compare(highItem(right), highItem(left)) > 0) {
                child = right;
            }

            if (order.compare(highItem(child), entryAt(node * 2 + 1).item) <= 0) {
                return;
            }

            swapSlots(node * 2 + 1, highSlot(child));
            if (hasHigh(child)
                    && order.compare(entryAt(child * 2).item,
                            entryAt(child * 2 + 1).item) > 0) {
                swapSlots(child * 2, child * 2 + 1);
            }
            node = child;
        }
    }

    private boolean hasHigh(int node) {
        return node * 2 + 1 < size;
    }

    private T highItem(int node) {
        return entryAt(highSlot(node)).item;
    }

    private int highSlot(int node) {
        return hasHigh(node) ? node * 2 + 1 : node * 2;
    }

    private void swapSlots(int first, int second) {
        if (first == second) {
            return;
        }
        Entry<T> firstEntry = entryAt(first);
        Entry<T> secondEntry = entryAt(second);
        entries[first] = secondEntry;
        entries[second] = firstEntry;
        firstEntry.slot = second;
        secondEntry.slot = first;
    }

    @SuppressWarnings("unchecked")
    private Entry<T> entryAt(int slot) {
        return (Entry<T>) entries[slot];
    }

    private void ensureCapacity(int required) {
        if (required <= entries.length) {
            return;
        }

        int newCapacity = entries.length;
        while (newCapacity < required) {
            int next = newCapacity << 1;
            if (next <= newCapacity) {
                newCapacity = required;
                break;
            }
            newCapacity = next;
        }

        Object[] grown = new Object[newCapacity];
        for (int i = 0; i < size; i++) {
            grown[i] = entries[i];
        }
        entries = grown;
    }
}

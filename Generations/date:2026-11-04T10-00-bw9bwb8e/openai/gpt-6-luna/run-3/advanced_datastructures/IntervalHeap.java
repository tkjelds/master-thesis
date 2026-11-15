interface Order<T> {
    int compare(T left, T right);
}

@IntervalHeap.ThreadSafe
public class IntervalHeap<T> {
    @interface ThreadSafe {}

    public static final class Handle<T> {
        private final Object owner;
        private int index;
        private boolean valid;

        private Handle(Object owner, int index) {
            this.owner = owner;
            this.index = index;
            this.valid = true;
        }
    }

    private static final class Entry<E> {
        private E item;
        private final Handle<E> handle;

        private Entry(E item, Handle<E> handle) {
            this.item = item;
            this.handle = handle;
        }
    }

    private final Order<T> order;
    private final Object owner = new Object();

    @SuppressWarnings("unchecked")
    private Entry<T>[] heap = (Entry<T>[]) new Entry[8];

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

        synchronized (this) {
            ensureCapacity(size + 1);
            Handle<T> handle = new Handle<T>(owner, size);
            heap[size] = new Entry<T>(item, handle);
            size++;
            repairNode((size - 1) / 2);
            return handle;
        }
    }

    public T findMin() {
        synchronized (this) {
            requireNotEmpty();
            return heap[0].item;
        }
    }

    public T findMax() {
        synchronized (this) {
            requireNotEmpty();
            return heap[size > 1 ? 1 : 0].item;
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
            int index = validateHandle(handle);
            return removeAt(index);
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
            int index = validateHandle(handle);
            Entry<T> entry = heap[index];
            T oldItem = entry.item;
            entry.item = item;
            repairNode(index / 2);
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

    private int validateHandle(Handle<T> handle) {
        int index = handle.index;
        if (handle.owner != owner
                || !handle.valid
                || index < 0
                || index >= size
                || heap[index] == null
                || heap[index].handle != handle) {
            throw new IllegalArgumentException("Foreign or invalid handle");
        }
        return index;
    }

    private void ensureCapacity(int required) {
        if (required <= heap.length) {
            return;
        }

        int newLength = heap.length * 2;
        if (newLength < required) {
            newLength = required;
        }
        if (newLength < 0) {
            throw new OutOfMemoryError("IntervalHeap is too large");
        }

        @SuppressWarnings("unchecked")
        Entry<T>[] grown = (Entry<T>[]) new Entry[newLength];
        for (int i = 0; i < size; i++) {
            grown[i] = heap[i];
        }
        heap = grown;
    }

    private T removeAt(int index) {
        Entry<T> removed = heap[index];
        T result = removed.item;
        int last = size - 1;

        removed.handle.valid = false;
        removed.handle.index = -1;

        if (index != last) {
            Entry<T> moved = heap[last];
            heap[index] = moved;
            moved.handle.index = index;
        }

        heap[last] = null;
        size--;

        if (index < size) {
            repairNode(index / 2);
        }
        return result;
    }

    private int nodeCount() {
        return (size + 1) / 2;
    }

    private boolean isSingleton(int node) {
        return node * 2 + 1 >= size;
    }

    private int lowSlot(int node) {
        return node * 2;
    }

    private int highSlot(int node) {
        return node * 2 + 1;
    }

    private void swapSlots(int first, int second) {
        if (first == second) {
            return;
        }

        Entry<T> temp = heap[first];
        heap[first] = heap[second];
        heap[second] = temp;

        heap[first].handle.index = first;
        heap[second].handle.index = second;
    }

    private void normalizeNode(int node) {
        if (!isSingleton(node)) {
            int low = lowSlot(node);
            int high = highSlot(node);
            if (order.compare(heap[low].item, heap[high].item) > 0) {
                swapSlots(low, high);
            }
        }
    }

    private void repairNode(int node) {
        normalizeNode(node);

        if (isSingleton(node)) {
            repairSingleton(node);
            return;
        }

        repairLow(node);
        repairHigh(node);
    }

    private void repairSingleton(int node) {
        int slot = lowSlot(node);
        int parent = (node - 1) / 2;

        if (node > 0) {
            int parentLow = lowSlot(parent);
            int parentHigh = highSlot(parent);
            int againstLow = order.compare(heap[slot].item, heap[parentLow].item);

            if (againstLow < 0) {
                while (node > 0) {
                    parent = (node - 1) / 2;
                    parentLow = lowSlot(parent);
                    if (order.compare(heap[slot].item, heap[parentLow].item) >= 0) {
                        break;
                    }
                    swapSlots(slot, parentLow);
                    node = parent;
                    slot = lowSlot(node);
                }
                return;
            }

            if (order.compare(heap[slot].item, heap[parentHigh].item) > 0) {
                while (node > 0) {
                    parent = (node - 1) / 2;
                    parentHigh = highSlot(parent);
                    if (order.compare(heap[slot].item, heap[parentHigh].item) <= 0) {
                        break;
                    }
                    swapSlots(slot, parentHigh);
                    node = parent;
                    slot = lowSlot(node);
                }
            }
        }
    }

    private void repairLow(int node) {
        int slot = lowSlot(node);

        if (node > 0) {
            int parent = (node - 1) / 2;
            int parentSlot = lowSlot(parent);
            if (order.compare(heap[slot].item, heap[parentSlot].item) < 0) {
                while (node > 0) {
                    parent = (node - 1) / 2;
                    parentSlot = lowSlot(parent);
                    if (order.compare(heap[slot].item, heap[parentSlot].item) >= 0) {
                        break;
                    }
                    swapSlots(slot, parentSlot);
                    normalizeNode(node);
                    node = parent;
                    slot = lowSlot(node);
                }
                return;
            }
        }

        int count = nodeCount();
        while (true) {
            int left = node * 2 + 1;
            if (left >= count) {
                break;
            }
            int right = left + 1;
            int child = left;
            if (right < count
                    && order.compare(heap[lowSlot(right)].item,
                                     heap[lowSlot(left)].item) < 0) {
                child = right;
            }

            int childSlot = lowSlot(child);
            if (order.compare(heap[childSlot].item, heap[slot].item) >= 0) {
                break;
            }

            swapSlots(slot, childSlot);
            normalizeNode(child);
            node = child;
            slot = lowSlot(node);
        }
    }

    private void repairHigh(int node) {
        int slot = highSlot(node);

        if (node > 0) {
            int parent = (node - 1) / 2;
            int parentSlot = highSlot(parent);
            if (order.compare(heap[slot].item, heap[parentSlot].item) > 0) {
                while (node > 0) {
                    parent = (node - 1) / 2;
                    parentSlot = highSlot(parent);
                    if (order.compare(heap[slot].item, heap[parentSlot].item) <= 0) {
                        break;
                    }
                    swapSlots(slot, parentSlot);
                    normalizeNode(node);
                    node = parent;
                    slot = highSlot(node);
                }
                return;
            }
        }

        int count = nodeCount();
        while (true) {
            int left = node * 2 + 1;
            if (left >= count) {
                break;
            }
            int right = left + 1;
            int child = left;
            if (right < count
                    && order.compare(heap[highSlot(right)].item,
                                     heap[highSlot(left)].item) > 0) {
                child = right;
            }

            int childSlot = highSlot(child);
            if (order.compare(heap[childSlot].item, heap[slot].item) <= 0) {
                break;
            }

            swapSlots(slot, childSlot);
            normalizeNode(child);
            node = child;
            slot = highSlot(node);
        }
    }
}

interface Order<T> {
    int compare(T left, T right);
}

@IntervalHeap.ThreadSafe
public final class IntervalHeap<T> {
    @interface ThreadSafe {}

    public static final class Handle<T> {
        private final Object owner;
        private Entry<T> entry;

        private Handle(Object owner, Entry<T> entry) {
            this.owner = owner;
            this.entry = entry;
        }
    }

    private static final class Entry<E> {
        private E item;
        private int slot;
        private boolean active;
        private Handle<E> handle;

        private Entry(E item, int slot) {
            this.item = item;
            this.slot = slot;
            this.active = true;
        }
    }

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
        synchronized (this) {
            ensureCapacity(size + 1);

            Entry<T> entry = new Entry<>(item, size);
            Handle<T> handle = new Handle<>(this, entry);
            entry.handle = handle;
            entries[size] = entry;
            size++;

            int node = (size - 1) / 2;
            if (node == 0) {
                return handle;
            }

            if ((size & 1) == 0) {
                int parent = (node - 1) / 2;
                int lowSlot = node * 2;
                int parentLow = parent * 2;
                int parentHigh = parentLow + 1;
                int lowComparison = order.compare(entries[lowSlot].item, entries[parentLow].item);
                if (lowComparison < 0) {
                    swapSlots(lowSlot, parentLow);
                    repairLow(parent);
                } else if (order.compare(entries[lowSlot].item, entries[parentHigh].item) > 0) {
                    swapSlots(lowSlot, parentHigh);
                    repairHigh(parent);
                }
            } else {
                int lowSlot = node * 2;
                int highSlot = lowSlot + 1;
                if (order.compare(entries[highSlot].item, entries[lowSlot].item) < 0) {
                    swapSlots(lowSlot, highSlot);
                    repairLow(node);
                } else if (order.compare(entries[highSlot].item, entries[lowSlot].item) > 0) {
                    repairHigh(node);
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
            return entries[size == 1 ? 0 : 1].item;
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
            return removeAt(size == 1 ? 0 : 1);
        }
    }

    public T delete(Handle<T> handle) {
        synchronized (this) {
            Entry<T> entry = requireValidHandle(handle);
            return removeAt(entry.slot);
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
            Entry<T> entry = requireValidHandle(handle);
            T oldItem = entry.item;
            entry.item = item;
            repairAt(entry.slot);
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
            throw new IllegalStateException("heap is empty");
        }
    }

    private Entry<T> requireValidHandle(Handle<T> handle) {
        if (handle == null) {
            throw new NullPointerException("handle");
        }
        if (handle.owner != this || handle.entry == null || !handle.entry.active
                || handle.entry.handle != handle) {
            throw new IllegalArgumentException("foreign or invalidated handle");
        }
        return handle.entry;
    }

    private T removeAt(int slot) {
        Entry<T> removed = entries[slot];
        int lastSlot = size - 1;

        if (slot != lastSlot) {
            Entry<T> moved = entries[lastSlot];
            entries[slot] = moved;
            moved.slot = slot;
        }
        entries[lastSlot] = null;
        size--;

        removed.active = false;
        removed.handle.entry = null;
        removed.handle = null;

        if (slot < size) {
            repairAt(slot);
        }
        return removed.item;
    }

    private void repairAt(int slot) {
        int node = slot / 2;
        if (hasHigh(node) && order.compare(entries[node * 2].item, entries[node * 2 + 1].item) > 0) {
            swapSlots(node * 2, node * 2 + 1);
        }
        repairLow(node);
        if (hasHigh(node)) {
            repairHigh(node);
        }
    }

    private void repairLow(int node) {
        if (node > 0) {
            int parent = (node - 1) / 2;
            if (order.compare(entries[node * 2].item, entries[parent * 2].item) < 0) {
                while (node > 0) {
                    parent = (node - 1) / 2;
                    if (order.compare(entries[node * 2].item, entries[parent * 2].item) >= 0) {
                        return;
                    }
                    swapSlots(node * 2, parent * 2);
                    node = parent;
                }
                return;
            }
        }

        while (true) {
            int left = node * 2 + 1;
            if (left >= (size + 1) / 2) {
                return;
            }
            int right = left + 1;
            int child = left;
            if (right < (size + 1) / 2
                    && order.compare(entries[right * 2].item, entries[left * 2].item) < 0) {
                child = right;
            }

            int lowSlot = node * 2;
            int childLow = child * 2;
            if (order.compare(entries[lowSlot].item, entries[childLow].item) <= 0) {
                return;
            }

            if (!hasHigh(child)) {
                swapSlots(lowSlot, childLow);
                return;
            }

            int childHigh = childLow + 1;
            if (order.compare(entries[lowSlot].item, entries[childHigh].item) <= 0) {
                swapSlots(lowSlot, childLow);
                node = child;
            } else {
                Entry<T> current = entries[lowSlot];
                Entry<T> childLowEntry = entries[childLow];
                Entry<T> childHighEntry = entries[childHigh];

                entries[lowSlot] = childLowEntry;
                childLowEntry.slot = lowSlot;
                entries[childLow] = childHighEntry;
                childHighEntry.slot = childLow;
                entries[childHigh] = current;
                current.slot = childHigh;
                node = child;
            }
        }
    }

    private void repairHigh(int node) {
        if (!hasHigh(node)) {
            return;
        }
        if (node > 0) {
            int parent = (node - 1) / 2;
            if (order.compare(entries[node * 2 + 1].item, entries[parent * 2 + 1].item) > 0) {
                while (node > 0) {
                    parent = (node - 1) / 2;
                    if (order.compare(entries[node * 2 + 1].item, entries[parent * 2 + 1].item) <= 0) {
                        return;
                    }
                    swapSlots(node * 2 + 1, parent * 2 + 1);
                    node = parent;
                }
                return;
            }
        }

        while (true) {
            int left = node * 2 + 1;
            if (left >= (size + 1) / 2) {
                return;
            }
            int right = left + 1;
            int child = left;
            if (right < (size + 1) / 2) {
                int rightHigh = hasHigh(right) ? right * 2 + 1 : right * 2;
                int leftHigh = hasHigh(left) ? left * 2 + 1 : left * 2;
                if (order.compare(entries[rightHigh].item, entries[leftHigh].item) > 0) {
                    child = right;
                }
            }

            int highSlot = node * 2 + 1;
            int childHigh = hasHigh(child) ? child * 2 + 1 : child * 2;
            if (order.compare(entries[highSlot].item, entries[childHigh].item) >= 0) {
                return;
            }

            if (!hasHigh(child)) {
                swapSlots(highSlot, childHigh);
                return;
            }

            int childLow = child * 2;
            if (order.compare(entries[highSlot].item, entries[childLow].item) >= 0) {
                swapSlots(highSlot, childHigh);
                node = child;
            } else {
                Entry<T> current = entries[highSlot];
                Entry<T> childLowEntry = entries[childLow];
                Entry<T> childHighEntry = entries[childHigh];

                entries[highSlot] = childHighEntry;
                childHighEntry.slot = highSlot;
                entries[childHigh] = childLowEntry;
                childLowEntry.slot = childHigh;
                entries[childLow] = current;
                current.slot = childLow;
                node = child;
            }
        }
    }

    private boolean hasHigh(int node) {
        return node * 2 + 1 < size;
    }

    private void swapSlots(int first, int second) {
        Entry<T> temporary = entries[first];
        entries[first] = entries[second];
        entries[second] = temporary;
        entries[first].slot = first;
        entries[second].slot = second;
    }

    @SuppressWarnings("unchecked")
    private void ensureCapacity(int required) {
        if (required <= entries.length) {
            return;
        }
        int newCapacity = entries.length * 2;
        if (newCapacity < required) {
            newCapacity = required;
        }
        Entry<T>[] grown = (Entry<T>[]) new Entry<?>[newCapacity];
        System.arraycopy(entries, 0, grown, 0, size);
        entries = grown;
    }
}

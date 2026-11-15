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
        private Handle<T> handle;
        private int slot;

        private Entry(T item, Handle<T> handle, int slot) {
            this.item = item;
            this.handle = handle;
            this.slot = slot;
        }
    }

    private final Order<T> order;
    private Object[] slots = new Object[8];
    private int size;

    public IntervalHeap(Order<T> order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        this.order = order;
    }

    public Handle<T> add(T item) {
        synchronized (this) {
            if (item == null) {
                throw new NullPointerException("item");
            }

            ensureCapacity(size + 1);
            int newSlot = size;
            Handle<T> handle = new Handle<T>(this, null);
            Entry<T> entry = new Entry<T>(item, handle, newSlot);
            handle.entry = entry;
            slots[newSlot] = entry;
            size++;

            int node = newSlot / 2;
            if ((newSlot & 1) == 1) {
                int lowSlot = node * 2;
                int highSlot = lowSlot + 1;

                if (order.compare(entryAt(lowSlot).item, entryAt(highSlot).item) > 0) {
                    swapSlots(lowSlot, highSlot);
                    repairLow(node);
                } else {
                    repairHigh(node);
                }
            } else if (node > 0) {
                int parent = (node - 1) / 2;
                int parentLow = parent * 2;
                int parentHigh = parentLow + 1;

                if (order.compare(entry.item, entryAt(parentLow).item) < 0) {
                    repairLow(node);
                } else if (order.compare(entry.item, entryAt(parentHigh).item) > 0) {
                    swapSlots(newSlot, parentHigh);
                    repairHigh(parent);
                }
            }

            return handle;
        }
    }

    public T findMin() {
        synchronized (this) {
            if (size == 0) {
                throw new IllegalStateException("heap is empty");
            }
            return entryAt(0).item;
        }
    }

    public T findMax() {
        synchronized (this) {
            if (size == 0) {
                throw new IllegalStateException("heap is empty");
            }
            return entryAt(size == 1 ? 0 : 1).item;
        }
    }

    public T deleteMin() {
        synchronized (this) {
            if (size == 0) {
                throw new IllegalStateException("heap is empty");
            }
            return removeAt(0);
        }
    }

    public T deleteMax() {
        synchronized (this) {
            if (size == 0) {
                throw new IllegalStateException("heap is empty");
            }
            return removeAt(size == 1 ? 0 : 1);
        }
    }

    public T delete(Handle<T> handle) {
        synchronized (this) {
            if (handle == null) {
                throw new NullPointerException("handle");
            }
            Entry<T> entry = validateHandle(handle);
            return removeAt(entry.slot);
        }
    }

    public T replace(Handle<T> handle, T item) {
        synchronized (this) {
            if (handle == null) {
                throw new NullPointerException("handle");
            }
            if (item == null) {
                throw new NullPointerException("item");
            }

            Entry<T> entry = validateHandle(handle);
            T oldItem = entry.item;
            entry.item = item;
            repairNode(entry.slot / 2);
            return oldItem;
        }
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    private Entry<T> validateHandle(Handle<T> handle) {
        if (handle.owner != this || handle.entry == null
                || handle.entry.handle != handle) {
            throw new IllegalArgumentException("foreign or invalid handle");
        }
        return handle.entry;
    }

    private Entry<T> entryAt(int slot) {
        @SuppressWarnings("unchecked")
        Entry<T> entry = (Entry<T>) slots[slot];
        return entry;
    }

    private void ensureCapacity(int required) {
        if (required <= slots.length) {
            return;
        }

        int newCapacity = slots.length * 2;
        if (newCapacity < required || newCapacity <= slots.length) {
            newCapacity = required;
        }

        Object[] grown = new Object[newCapacity];
        for (int i = 0; i < size; i++) {
            grown[i] = slots[i];
        }
        slots = grown;
    }

    private void swapSlots(int first, int second) {
        Entry<T> a = entryAt(first);
        Entry<T> b = entryAt(second);

        slots[first] = b;
        slots[second] = a;
        a.slot = second;
        b.slot = first;
    }

    private T removeAt(int targetSlot) {
        Entry<T> removed = entryAt(targetSlot);
        T result = removed.item;
        int lastSlot = size - 1;

        if (targetSlot != lastSlot) {
            Entry<T> last = entryAt(lastSlot);
            slots[targetSlot] = last;
            last.slot = targetSlot;
        }
        slots[lastSlot] = null;
        size--;

        removed.handle.entry = null;
        removed.handle = null;

        if (targetSlot != lastSlot && size > 0) {
            repairNode(targetSlot / 2);
        }

        return result;
    }

    private void repairNode(int node) {
        int lowSlot = node * 2;
        int highSlot = lowSlot + 1;

        if (highSlot < size
                && order.compare(entryAt(lowSlot).item, entryAt(highSlot).item) > 0) {
            swapSlots(lowSlot, highSlot);
        }

        repairLow(node);
        if (highSlot < size) {
            repairHigh(node);
        }
    }

    private void repairLow(int node) {
        int current = node;
        if (current > 0) {
            int parent = (current - 1) / 2;
            if (order.compare(entryAt(current * 2).item,
                              entryAt(parent * 2).item) < 0) {
                while (current > 0) {
                    parent = (current - 1) / 2;
                    if (order.compare(entryAt(current * 2).item,
                                      entryAt(parent * 2).item) >= 0) {
                        break;
                    }
                    swapSlots(current * 2, parent * 2);
                    current = parent;
                }
                return;
            }
        }

        int nodeCount = size / 2 + size % 2;
        while (true) {
            int left = current * 2 + 1;
            if (left >= nodeCount) {
                return;
            }
            int right = left + 1;
            int child = left;
            if (right < nodeCount
                    && order.compare(entryAt(right * 2).item,
                                     entryAt(left * 2).item) < 0) {
                child = right;
            }
            if (order.compare(entryAt(child * 2).item,
                              entryAt(current * 2).item) >= 0) {
                return;
            }
            swapSlots(current * 2, child * 2);
            current = child;
        }
    }

    private void repairHigh(int node) {
        int current = node;
        if (current > 0) {
            int parent = (current - 1) / 2;
            if (order.compare(entryAt(current * 2 + 1).item,
                              entryAt(parent * 2 + 1).item) > 0) {
                while (current > 0) {
                    parent = (current - 1) / 2;
                    if (order.compare(entryAt(current * 2 + 1).item,
                                      entryAt(parent * 2 + 1).item) <= 0) {
                        break;
                    }
                    swapSlots(current * 2 + 1, parent * 2 + 1);
                    current = parent;
                }
                return;
            }
        }

        int highNodeCount = size / 2;
        while (true) {
            int left = current * 2 + 1;
            if (left >= highNodeCount) {
                return;
            }
            int right = left + 1;
            int child = left;
            if (right < highNodeCount
                    && order.compare(entryAt(right * 2 + 1).item,
                                     entryAt(left * 2 + 1).item) > 0) {
                child = right;
            }
            if (order.compare(entryAt(child * 2 + 1).item,
                              entryAt(current * 2 + 1).item) <= 0) {
                return;
            }
            swapSlots(current * 2 + 1, child * 2 + 1);
            current = child;
        }
    }
}

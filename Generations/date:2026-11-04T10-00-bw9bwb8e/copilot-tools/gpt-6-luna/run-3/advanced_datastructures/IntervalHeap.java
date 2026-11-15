@IntervalHeap.ThreadSafe
public final class IntervalHeap<T> {
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
        private int index;
        private boolean valid;
        private Handle<E> handle;

        private Entry(E item) {
            this.item = item;
            this.index = -1;
            this.valid = true;
        }
    }

    private final Object monitor = new Object();
    private final Order<? super T> order;
    private Entry<T>[] heap;
    private int size;

    @SuppressWarnings("unchecked")
    public IntervalHeap(Order<? super T> order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        this.order = order;
        this.heap = (Entry<T>[]) new Entry<?>[8];
    }

    public Handle<T> add(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (monitor) {
            ensureCapacity(size + 1);
            Entry<T> entry = new Entry<T>(item);
            Handle<T> handle = new Handle<T>(this, entry);
            entry.handle = handle;

            int index = size;
            heap[index] = entry;
            entry.index = index;
            size++;

            if ((index & 1) == 1) {
                int low = index - 1;
                if (compare(heap[index].item, heap[low].item) < 0) {
                    swap(index, low);
                    repairLow(low);
                } else {
                    repairHigh(index);
                }
            } else if (index >= 2) {
                int node = index >>> 1;
                int parent = (node - 1) >>> 1;
                int parentLow = parent << 1;
                int parentHigh = parentLow + 1;
                if (compare(heap[index].item, heap[parentLow].item) < 0) {
                    swap(index, parentLow);
                    repairLow(parentLow);
                } else if (compare(heap[index].item, heap[parentHigh].item) > 0) {
                    swap(index, parentHigh);
                    repairHigh(parentHigh);
                }
            }
            return handle;
        }
    }

    public T findMin() {
        synchronized (monitor) {
            requireNotEmpty();
            return heap[0].item;
        }
    }

    public T findMax() {
        synchronized (monitor) {
            requireNotEmpty();
            if (size == 1) {
                return heap[0].item;
            }
            int maximum = 1;
            if ((size & 1) == 1 && compare(heap[size - 1].item, heap[maximum].item) > 0) {
                maximum = size - 1;
            }
            return heap[maximum].item;
        }
    }

    public T deleteMin() {
        synchronized (monitor) {
            requireNotEmpty();
            Entry<T> removed = heap[0];
            T item = removed.item;
            removeEntry(removed);
            return item;
        }
    }

    public T deleteMax() {
        synchronized (monitor) {
            requireNotEmpty();
            int maximum = size == 1 ? 0 : 1;
            if (size > 1 && (size & 1) == 1
                    && compare(heap[size - 1].item, heap[maximum].item) > 0) {
                maximum = size - 1;
            }
            Entry<T> removed = heap[maximum];
            T item = removed.item;
            removeEntry(removed);
            return item;
        }
    }

    public T delete(Handle<T> handle) {
        if (handle == null) {
            throw new NullPointerException("handle");
        }
        synchronized (monitor) {
            Entry<T> entry = validate(handle);
            T item = entry.item;
            removeEntry(entry);
            return item;
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
            T old = entry.item;
            entry.item = item;
            repairAt(entry.index);
            return old;
        }
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    private Entry<T> validate(Handle<T> handle) {
        if (handle.owner != this || !handle.entry.valid) {
            throw new IllegalArgumentException("foreign or invalidated handle");
        }
        return handle.entry;
    }

    private void removeEntry(Entry<T> removed) {
        int index = removed.index;
        int last = size - 1;
        removed.valid = false;
        removed.index = -1;
        if (index != last) {
            Entry<T> replacement = heap[last];
            heap[index] = replacement;
            replacement.index = index;
        }
        heap[last] = null;
        size--;
        if (index < size) {
            repairAt(index);
        }
    }

    private void repairAt(int index) {
        if (index < size) {
            if ((index & 1) == 0 && index + 1 < size
                    && compare(heap[index].item, heap[index + 1].item) > 0) {
                swap(index, index + 1);
                repairHigh(index + 1);
                repairLow(index);
            } else if ((index & 1) == 1
                    && compare(heap[index - 1].item, heap[index].item) > 0) {
                swap(index - 1, index);
                repairLow(index - 1);
                repairHigh(index);
            } else if ((index & 1) == 0) {
                repairLow(index);
                if (index + 1 == size && size > 1) {
                    repairHigh(index);
                }
            } else {
                repairHigh(index);
            }
        }
    }

    private void repairLow(int index) {
        if (index >= size) {
            return;
        }
        int node = index >>> 1;
        int low = node << 1;
        int high = low + 1;
        if (high < size && compare(heap[low].item, heap[high].item) > 0) {
            swap(low, high);
            repairHigh(high);
            repairLow(low);
            return;
        }

        int parentNode = (node - 1) >>> 1;
        if (node > 0 && compare(heap[low].item, heap[parentNode << 1].item) < 0) {
            swap(low, parentNode << 1);
            repairLow(low);
            repairLow(parentNode << 1);
            return;
        }

        while (true) {
            int leftNode = (node << 1) + 1;
            if ((leftNode << 1) >= size) {
                return;
            }
            int rightNode = leftNode + 1;
            int child = leftNode;
            if ((rightNode << 1) < size
                    && compare(heap[rightNode << 1].item, heap[leftNode << 1].item) < 0) {
                child = rightNode;
            }
            int childLow = child << 1;
            if (compare(heap[childLow].item, heap[low].item) >= 0) {
                return;
            }
            swap(low, childLow);
            int childHigh = childLow + 1;
            if (childHigh < size && compare(heap[childLow].item, heap[childHigh].item) > 0) {
                swap(childLow, childHigh);
                repairHigh(childHigh);
            }
            node = child;
            low = childLow;
        }
    }

    private void repairHigh(int index) {
        if (index >= size || size < 2) {
            return;
        }
        int node = index >>> 1;
        int low = node << 1;
        int high = low + 1 < size ? low + 1 : low;
        if (high != low && compare(heap[low].item, heap[high].item) > 0) {
            swap(low, high);
            repairLow(low);
            repairHigh(high);
            return;
        }

        int parentNode = (node - 1) >>> 1;
        if (node > 0 && compare(heap[high].item, heap[(parentNode << 1) + 1].item) > 0) {
            swap(high, (parentNode << 1) + 1);
            repairHigh(high);
            repairHigh((parentNode << 1) + 1);
            return;
        }

        while (true) {
            int leftNode = (node << 1) + 1;
            int leftLow = leftNode << 1;
            if (leftLow >= size) {
                return;
            }
            int leftHigh = leftLow + 1 < size ? leftLow + 1 : leftLow;
            int rightNode = leftNode + 1;
            int child = leftNode;
            int rightLow = rightNode << 1;
            int rightHigh = rightLow < size ? (rightLow + 1 < size ? rightLow + 1 : rightLow) : -1;
            if (rightHigh >= 0 && compare(heap[rightHigh].item, heap[leftHigh].item) > 0) {
                child = rightNode;
            }
            int childLow = child << 1;
            int childHigh = childLow + 1 < size ? childLow + 1 : childLow;
            if (compare(heap[childHigh].item, heap[high].item) <= 0) {
                return;
            }
            swap(high, childHigh);
            if (childLow != childHigh && compare(heap[childLow].item, heap[childHigh].item) > 0) {
                swap(childLow, childHigh);
                repairLow(childLow);
            }
            node = child;
            high = childHigh;
        }
    }

    private void swap(int left, int right) {
        Entry<T> temporary = heap[left];
        heap[left] = heap[right];
        heap[right] = temporary;
        heap[left].index = left;
        heap[right].index = right;
    }

    private int compare(T left, T right) {
        return order.compare(left, right);
    }

    private void requireNotEmpty() {
        if (size == 0) {
            throw new IllegalStateException("interval heap is empty");
        }
    }

    @SuppressWarnings("unchecked")
    private void ensureCapacity(int required) {
        if (required > heap.length) {
            int capacity = heap.length * 2;
            if (capacity < required) {
                capacity = required;
            }
            Entry<T>[] expanded = (Entry<T>[]) new Entry<?>[capacity];
            System.arraycopy(heap, 0, expanded, 0, size);
            heap = expanded;
        }
    }
}

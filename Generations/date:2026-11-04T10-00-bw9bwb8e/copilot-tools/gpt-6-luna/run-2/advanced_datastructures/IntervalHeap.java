@IntervalHeap.ThreadSafe
public class IntervalHeap<T> {
    @interface ThreadSafe {}

    public interface Order<E> {
        int compare(E left, E right);
    }

    public static final class Handle<E> {
        private final IntervalHeap<?> owner;
        private int index;
        private boolean active;

        private Handle(IntervalHeap<?> owner, int index) {
            this.owner = owner;
            this.index = index;
            this.active = true;
        }
    }

    private final Order<T> order;
    private Object[] items = new Object[8];
    private Handle<?>[] handles = new Handle<?>[8];
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
            if (size == Integer.MAX_VALUE) {
                throw new OutOfMemoryError("IntervalHeap is too large");
            }

            ensureCapacity(size + 1);
            int index = size;
            Handle<T> handle = new Handle<T>(this, index);
            items[index] = item;
            handles[index] = handle;
            size++;

            int node = index >>> 1;
            if ((index & 1) == 0) {
                if (node > 0) {
                    int parent = (node - 1) >>> 1;
                    if (compare(index, lowIndex(parent)) < 0) {
                        bubbleLowUp(node);
                    } else if (compare(index, highIndex(parent)) > 0) {
                        bubbleHighUp(node);
                    }
                }
            } else {
                int low = lowIndex(node);
                if (compare(index, low) < 0) {
                    Handle<?> oldLowHandle = handles[low];
                    T oldLowItem = itemAt(low);
                    assign(index, oldLowItem, oldLowHandle);
                    assign(low, item, handle);
                    bubbleLowUp(node);
                } else {
                    bubbleHighUp(node);
                }
            }
            return handle;
        }
    }

    public T findMin() {
        synchronized (this) {
            if (size == 0) {
                throw new IllegalStateException("IntervalHeap is empty");
            }
            return itemAt(0);
        }
    }

    public T findMax() {
        synchronized (this) {
            if (size == 0) {
                throw new IllegalStateException("IntervalHeap is empty");
            }
            return itemAt(size == 1 ? 0 : 1);
        }
    }

    public T deleteMin() {
        synchronized (this) {
            if (size == 0) {
                throw new IllegalStateException("IntervalHeap is empty");
            }
            return removeAt(0);
        }
    }

    public T deleteMax() {
        synchronized (this) {
            if (size == 0) {
                throw new IllegalStateException("IntervalHeap is empty");
            }
            return removeAt(size == 1 ? 0 : 1);
        }
    }

    public T delete(Handle<T> handle) {
        synchronized (this) {
            validateHandle(handle);
            return removeAt(handle.index);
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
            validateHandle(handle);

            int index = handle.index;
            T oldItem = itemAt(index);
            items[index] = item;
            repairAt(index >>> 1);
            return oldItem;
        }
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    private void validateHandle(Handle<T> handle) {
        if (handle == null) {
            throw new NullPointerException("handle");
        }
        int index = handle.index;
        if (handle.owner != this || !handle.active || index < 0 || index >= size
                || handles[index] != handle) {
            throw new IllegalArgumentException("Handle is foreign or invalidated");
        }
    }

    private T removeAt(int index) {
        T removedItem = itemAt(index);
        Handle<?> removedHandle = handles[index];
        int last = size - 1;

        if (index != last) {
            assign(index, itemAt(last), handles[last]);
        }
        items[last] = null;
        handles[last] = null;
        size--;

        removedHandle.index = -1;
        removedHandle.active = false;

        if (index < size) {
            repairAt(index >>> 1);
        }
        return removedItem;
    }

    private void repairAt(int node) {
        if (!hasHigh(node)) {
            if (node > 0) {
                int parent = (node - 1) >>> 1;
                int index = lowIndex(node);
                if (compare(index, lowIndex(parent)) < 0) {
                    bubbleLowUp(node);
                } else if (compare(index, highIndex(parent)) > 0) {
                    bubbleHighUp(node);
                }
            }
        } else if ((node << 1) < size) {
            repairLowAt(node);
        } else {
            repairHighAt(node);
        }
    }

    private void repairLowAt(int node) {
        if (!hasHigh(node)) {
            if (node > 0) {
                int parent = (node - 1) >>> 1;
                int index = lowIndex(node);
                if (compare(index, lowIndex(parent)) < 0) {
                    bubbleLowUp(node);
                } else if (compare(index, highIndex(parent)) > 0) {
                    bubbleHighUp(node);
                }
            }
            return;
        }

        int low = lowIndex(node);
        int high = highIndex(node);
        if (compare(low, high) > 0) {
            swap(low, high);
            siftLowDown(node);
            bubbleHighUp(node);
            return;
        }

        if (node > 0 && compare(low, lowIndex((node - 1) >>> 1)) < 0) {
            bubbleLowUp(node);
        } else {
            siftLowDown(node);
        }
    }

    private void repairHighAt(int node) {
        if (!hasHigh(node)) {
            if (node > 0) {
                int parent = (node - 1) >>> 1;
                int index = lowIndex(node);
                if (compare(index, lowIndex(parent)) < 0) {
                    bubbleLowUp(node);
                } else if (compare(index, highIndex(parent)) > 0) {
                    bubbleHighUp(node);
                }
            }
            return;
        }

        int low = lowIndex(node);
        int high = highIndex(node);
        if (compare(high, low) < 0) {
            swap(low, high);
            bubbleLowUp(node);
            siftHighDown(node);
            return;
        }

        if (node > 0 && compare(high, highIndex((node - 1) >>> 1)) > 0) {
            bubbleHighUp(node);
        } else {
            siftHighDown(node);
        }
    }

    private void bubbleLowUp(int node) {
        while (node > 0) {
            int parent = (node - 1) >>> 1;
            int low = lowIndex(node);
            int parentLow = lowIndex(parent);
            if (compare(low, parentLow) >= 0) {
                return;
            }
            swap(low, parentLow);
            node = parent;
        }
    }

    private void bubbleHighUp(int node) {
        while (node > 0) {
            int parent = (node - 1) >>> 1;
            int high = highIndex(node);
            int parentHigh = highIndex(parent);
            if (compare(high, parentHigh) <= 0) {
                return;
            }
            swap(high, parentHigh);
            node = parent;
        }
    }

    private void siftLowDown(int node) {
        int nodeCount = (size >>> 1) + (size & 1);
        while (true) {
            int child = (node << 1) + 1;
            if (child >= nodeCount) {
                return;
            }
            int right = child + 1;
            if (right < nodeCount && compare(lowIndex(right), lowIndex(child)) < 0) {
                child = right;
            }

            int low = lowIndex(node);
            int childLow = lowIndex(child);
            if (compare(low, childLow) <= 0) {
                return;
            }
            swap(low, childLow);

            if (!hasHigh(child)) {
                bubbleHighUp(child);
                return;
            }

            int childHigh = highIndex(child);
            if (compare(childLow, childHigh) > 0) {
                swap(childLow, childHigh);
                bubbleHighUp(child);
            }
            node = child;
        }
    }

    private void siftHighDown(int node) {
        int nodeCount = (size >>> 1) + (size & 1);
        while (true) {
            int child = (node << 1) + 1;
            if (child >= nodeCount) {
                return;
            }
            int right = child + 1;
            if (right < nodeCount && compare(highIndex(right), highIndex(child)) > 0) {
                child = right;
            }

            int high = highIndex(node);
            int childHigh = highIndex(child);
            if (compare(high, childHigh) >= 0) {
                return;
            }
            swap(high, childHigh);

            if (!hasHigh(child)) {
                bubbleLowUp(child);
                return;
            }

            int childLow = lowIndex(child);
            if (compare(childHigh, childLow) < 0) {
                swap(childLow, childHigh);
                bubbleLowUp(child);
            }
            node = child;
        }
    }

    private int lowIndex(int node) {
        return node << 1;
    }

    private int highIndex(int node) {
        int high = (node << 1) + 1;
        return high < size ? high : (node << 1);
    }

    private boolean hasHigh(int node) {
        return (node << 1) + 1 < size;
    }

    private int compare(int leftIndex, int rightIndex) {
        return order.compare(itemAt(leftIndex), itemAt(rightIndex));
    }

    @SuppressWarnings("unchecked")
    private T itemAt(int index) {
        return (T) items[index];
    }

    private void assign(int index, T item, Handle<?> handle) {
        items[index] = item;
        handles[index] = handle;
        handle.index = index;
    }

    private void swap(int left, int right) {
        Object item = items[left];
        items[left] = items[right];
        items[right] = item;

        Handle<?> handle = handles[left];
        handles[left] = handles[right];
        handles[right] = handle;

        handles[left].index = left;
        handles[right].index = right;
    }

    private void ensureCapacity(int needed) {
        if (needed <= items.length) {
            return;
        }

        int capacity = items.length;
        while (capacity < needed) {
            if (capacity > Integer.MAX_VALUE / 2) {
                capacity = Integer.MAX_VALUE;
            } else {
                capacity *= 2;
            }
        }

        Object[] newItems = new Object[capacity];
        Handle<?>[] newHandles = new Handle<?>[capacity];
        for (int i = 0; i < size; i++) {
            newItems[i] = items[i];
            newHandles[i] = handles[i];
        }
        items = newItems;
        handles = newHandles;
    }
}

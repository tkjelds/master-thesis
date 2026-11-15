@IntervalHeap.ThreadSafe
public final class IntervalHeap<T> {
    @interface ThreadSafe {}

    public interface Order<E> {
        int compare(E left, E right);
    }

    public static final class Handle<E> {
        private final IntervalHeap<?> owner;
        private Entry<E> entry;

        private Handle(IntervalHeap<?> owner) {
            this.owner = owner;
        }
    }

    private static final class Entry<E> {
        private E item;
        private final Handle<E> handle;
        private int node;
        private boolean high;

        private Entry(E item, Handle<E> handle) {
            this.item = item;
            this.handle = handle;
        }
    }

    private final Order<T> order;
    private Entry<T>[] lows;
    private Entry<T>[] highs;
    private int count;

    @SuppressWarnings("unchecked")
    public IntervalHeap(Order<T> order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        this.order = order;
        this.lows = (Entry<T>[]) new Entry<?>[4];
        this.highs = (Entry<T>[]) new Entry<?>[4];
    }

    public Handle<T> add(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (this) {
            int oldCount = count;
            int node = oldCount / 2;
            ensureCapacity(node + 1);

            Handle<T> handle = new Handle<T>(this);
            Entry<T> entry = new Entry<T>(item, handle);
            handle.entry = entry;

            if ((oldCount & 1) == 0) {
                setLow(node, entry);
                count++;
                if (node > 0) {
                    int parent = (node - 1) / 2;
                    if (compare(entry.item, lows[parent].item) < 0) {
                        fixEntry(entry);
                    } else if (compare(entry.item, highs[parent].item) > 0) {
                        swap(node, false, parent, true);
                        fixEntry(entry);
                    }
                }
            } else {
                Entry<T> low = lows[node];
                setHigh(node, entry);
                count++;
                if (compare(entry.item, low.item) < 0) {
                    swap(node, false, node, true);
                    fixEntry(entry);
                } else {
                    fixEntry(entry);
                }
            }
            return handle;
        }
    }

    public T findMin() {
        synchronized (this) {
            requireNotEmpty();
            return lows[0].item;
        }
    }

    public T findMax() {
        synchronized (this) {
            requireNotEmpty();
            return count == 1 ? lows[0].item : highs[0].item;
        }
    }

    public T deleteMin() {
        synchronized (this) {
            requireNotEmpty();
            return removeEntry(lows[0]);
        }
    }

    public T deleteMax() {
        synchronized (this) {
            requireNotEmpty();
            return removeEntry(count == 1 ? lows[0] : highs[0]);
        }
    }

    public T delete(Handle<T> handle) {
        if (handle == null) {
            throw new NullPointerException("handle");
        }
        synchronized (this) {
            Entry<T> entry = validateHandle(handle);
            return removeEntry(entry);
        }
    }

    public T replace(Handle<T> handle, T item) {
        if (handle == null || item == null) {
            throw new NullPointerException();
        }
        synchronized (this) {
            Entry<T> entry = validateHandle(handle);
            T oldItem = entry.item;
            entry.item = item;
            fixEntry(entry);
            return oldItem;
        }
    }

    public int size() {
        synchronized (this) {
            return count;
        }
    }

    private Entry<T> validateHandle(Handle<T> handle) {
        if (handle.owner != this || handle.entry == null
                || handle.entry.handle != handle) {
            throw new IllegalArgumentException("foreign or invalid handle");
        }
        return handle.entry;
    }

    private void requireNotEmpty() {
        if (count == 0) {
            throw new IllegalStateException("heap is empty");
        }
    }

    private int nodeCount() {
        return (count + 1) / 2;
    }

    private void ensureCapacity(int needed) {
        if (needed <= lows.length) {
            return;
        }
        int newLength = lows.length;
        while (newLength < needed) {
            newLength = newLength * 2;
        }
        @SuppressWarnings("unchecked")
        Entry<T>[] newLows = (Entry<T>[]) new Entry<?>[newLength];
        @SuppressWarnings("unchecked")
        Entry<T>[] newHighs = (Entry<T>[]) new Entry<?>[newLength];
        System.arraycopy(lows, 0, newLows, 0, lows.length);
        System.arraycopy(highs, 0, newHighs, 0, highs.length);
        lows = newLows;
        highs = newHighs;
    }

    private void setLow(int node, Entry<T> entry) {
        lows[node] = entry;
        if (entry != null) {
            entry.node = node;
            entry.high = false;
        }
    }

    private void setHigh(int node, Entry<T> entry) {
        highs[node] = entry;
        if (entry != null) {
            entry.node = node;
            entry.high = true;
        }
    }

    private void swap(int firstNode, boolean firstHigh,
                      int secondNode, boolean secondHigh) {
        Entry<T> first = firstHigh ? highs[firstNode] : lows[firstNode];
        Entry<T> second = secondHigh ? highs[secondNode] : lows[secondNode];

        if (firstHigh) {
            setHigh(firstNode, second);
        } else {
            setLow(firstNode, second);
        }
        if (secondHigh) {
            setHigh(secondNode, first);
        } else {
            setLow(secondNode, first);
        }
    }

    private void normalize(int node) {
        if (highs[node] != null && compare(lows[node].item, highs[node].item) > 0) {
            swap(node, false, node, true);
        }
    }

    /*
     * Repairs the path of an entry whose key or position has changed. Endpoint
     * swaps preserve handle locations through setLow/setHigh.
     */
    private void fixEntry(Entry<T> entry) {
        while (true) {
            int node = entry.node;
            normalize(node);

            if (highs[node] == null) {
                if (node == 0) {
                    return;
                }
                int parent = (node - 1) / 2;
                if (compare(lows[node].item, lows[parent].item) < 0) {
                    swap(node, false, parent, false);
                    continue;
                }
                if (compare(lows[node].item, highs[parent].item) > 0) {
                    swap(node, false, parent, true);
                    continue;
                }
                return;
            }

            if (!entry.high) {
                if (node > 0) {
                    int parent = (node - 1) / 2;
                    if (compare(lows[node].item, lows[parent].item) < 0) {
                        swap(node, false, parent, false);
                        continue;
                    }
                }

                int left = node * 2 + 1;
                int right = left + 1;
                int child = -1;
                int nodes = nodeCount();
                if (left < nodes) {
                    child = left;
                }
                if (right < nodes
                        && (child < 0
                        || compare(lows[right].item, lows[child].item) < 0)) {
                    child = right;
                }
                if (child >= 0 && compare(lows[node].item, lows[child].item) > 0) {
                    swap(node, false, child, false);
                    continue;
                }
                return;
            } else {
                if (node > 0) {
                    int parent = (node - 1) / 2;
                    if (compare(highs[node].item, highs[parent].item) > 0) {
                        swap(node, true, parent, true);
                        continue;
                    }
                }

                int left = node * 2 + 1;
                int right = left + 1;
                int child = -1;
                int nodes = nodeCount();
                if (left < nodes && highs[left] != null) {
                    child = left;
                }
                if (right < nodes && highs[right] != null
                        && (child < 0
                        || compare(highs[right].item, highs[child].item) > 0)) {
                    child = right;
                }
                if (child >= 0 && compare(highs[node].item, highs[child].item) < 0) {
                    swap(node, true, child, true);
                    continue;
                }
                return;
            }
        }
    }

    private T removeEntry(Entry<T> target) {
        T removedItem = target.item;
        int targetNode = target.node;
        boolean targetHigh = target.high;

        int oldCount = count;
        int lastNode = (oldCount - 1) / 2;
        boolean lastHigh = (oldCount & 1) == 0;
        Entry<T> lastEntry = lastHigh ? highs[lastNode] : lows[lastNode];

        if (target != lastEntry) {
            swap(targetNode, targetHigh, lastNode, lastHigh);
        }

        if (lastHigh) {
            highs[lastNode] = null;
        } else {
            lows[lastNode] = null;
            highs[lastNode] = null;
        }
        count--;

        target.handle.entry = null;

        if (target != lastEntry) {
            fixEntry(lastEntry);
        }
        return removedItem;
    }

    private int compare(T left, T right) {
        return order.compare(left, right);
    }
}

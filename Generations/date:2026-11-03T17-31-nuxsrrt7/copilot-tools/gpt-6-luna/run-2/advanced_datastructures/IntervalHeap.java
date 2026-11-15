@IntervalHeap.ThreadSafe
public final class IntervalHeap<T> {
    @interface ThreadSafe {}

    public interface Order<E> {
        int compare(E left, E right);
    }

    public static final class Handle<E> {
        private final Object owner;
        private final Entry<E> entry;

        private Handle(Object owner, Entry<E> entry) {
            this.owner = owner;
            this.entry = entry;
        }
    }

    private static final class Entry<E> {
        E item;
        Handle<E> handle;
        int node;
        boolean high;
        boolean alive = true;

        Entry(E item) {
            this.item = item;
        }
    }

    private static final class Node<E> {
        Entry<E> low;
        Entry<E> high;
    }

    private final Order<? super T> order;
    private final Object owner = new Object();
    private final Object monitor = new Object();
    private Node<T>[] nodes;
    private int size;

    @SuppressWarnings("unchecked")
    public IntervalHeap(Order<? super T> order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        this.order = order;
        this.nodes = (Node<T>[]) new Node<?>[4];
    }

    public Handle<T> add(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (monitor) {
            Entry<T> entry = new Entry<>(item);
            Handle<T> handle = new Handle<>(owner, entry);
            entry.handle = handle;

            int nodeIndex = size / 2;
            if ((size & 1) == 0) {
                ensureNodeCapacity(nodeIndex + 1);
                Node<T> node = new Node<>();
                nodes[nodeIndex] = node;
                node.low = entry;
                setLocation(entry, nodeIndex, false);
                size++;
            } else {
                Node<T> node = nodes[nodeIndex];
                Entry<T> existing = node.low;
                if (compare(item, existing.item) < 0) {
                    node.low = entry;
                    node.high = existing;
                    setLocation(entry, nodeIndex, false);
                    setLocation(existing, nodeIndex, true);
                } else {
                    node.high = entry;
                    setLocation(entry, nodeIndex, true);
                }
                size++;
            }

            repair(entry);
            return handle;
        }
    }

    public T findMin() {
        synchronized (monitor) {
            requireNotEmpty();
            return nodes[0].low.item;
        }
    }

    public T findMax() {
        synchronized (monitor) {
            requireNotEmpty();
            Node<T> root = nodes[0];
            return (size == 1 ? root.low : root.high).item;
        }
    }

    public T deleteMin() {
        synchronized (monitor) {
            requireNotEmpty();
            return removeRoot(false).item;
        }
    }

    public T deleteMax() {
        synchronized (monitor) {
            requireNotEmpty();
            return removeRoot(size > 1).item;
        }
    }

    public T delete(Handle<T> handle) {
        if (handle == null) {
            throw new NullPointerException("handle");
        }
        synchronized (monitor) {
            Entry<T> entry = validate(handle);
            T item = entry.item;
            boolean high = entry.high && nodes[entry.node].high != null;

            while (entry.node > 0) {
                int parent = (entry.node - 1) / 2;
                swapSlots(entry, nodes[parent].low == null ? false : high, parent, high);
            }

            removeRoot(high);
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
            T oldItem = entry.item;
            entry.item = item;
            repair(entry);
            return oldItem;
        }
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    private Entry<T> validate(Handle<T> handle) {
        if (handle.owner != owner || handle.entry == null || !handle.entry.alive) {
            throw new IllegalArgumentException("foreign or invalidated handle");
        }
        return handle.entry;
    }

    private void requireNotEmpty() {
        if (size == 0) {
            throw new IllegalStateException("heap is empty");
        }
    }

    private int compare(T left, T right) {
        return order.compare(left, right);
    }

    private void ensureNodeCapacity(int required) {
        if (required <= nodes.length) {
            return;
        }
        int capacity = nodes.length;
        while (capacity < required) {
            int next = capacity <= Integer.MAX_VALUE / 2
                    ? capacity * 2 : Integer.MAX_VALUE;
            if (next == capacity) {
                throw new OutOfMemoryError("interval heap is too large");
            }
            capacity = next;
        }
        Node<T>[] grown = newNodeArray(capacity);
        System.arraycopy(nodes, 0, grown, 0, nodes.length);
        nodes = grown;
    }

    @SuppressWarnings("unchecked")
    private Node<T>[] newNodeArray(int capacity) {
        return (Node<T>[]) new Node<?>[capacity];
    }

    private void setLocation(Entry<T> entry, int node, boolean high) {
        entry.node = node;
        entry.high = high;
    }

    private void swapSlots(Entry<T> first, boolean firstHigh,
                           int secondNode, boolean secondHigh) {
        int firstNode = first.node;
        Node<T> a = nodes[firstNode];
        Node<T> b = nodes[secondNode];
        Entry<T> second = secondHigh ? b.high : b.low;

        if (firstHigh) {
            a.high = second;
        } else {
            a.low = second;
        }
        if (secondHigh) {
            b.high = first;
        } else {
            b.low = first;
        }

        setLocation(first, secondNode, secondHigh);
        setLocation(second, firstNode, firstHigh);
    }

    private void swapWithinNode(int nodeIndex) {
        Node<T> node = nodes[nodeIndex];
        Entry<T> low = node.low;
        Entry<T> high = node.high;
        node.low = high;
        node.high = low;
        setLocation(high, nodeIndex, false);
        setLocation(low, nodeIndex, true);
    }

    private void repair(Entry<T> entry) {
        int nodeIndex = entry.node;
        Node<T> node = nodes[nodeIndex];

        if (node.high == null) {
            if (nodeIndex == 0) {
                return;
            }
            int parentIndex = (nodeIndex - 1) / 2;
            Node<T> parent = nodes[parentIndex];
            if (compare(entry.item, parent.low.item) < 0) {
                swapSlots(entry, false, parentIndex, false);
            } else if (compare(entry.item, parent.high.item) > 0) {
                swapSlots(entry, false, parentIndex, true);
            }
            return;
        }

        if (!entry.high && compare(entry.item, node.high.item) > 0) {
            Entry<T> other = node.high;
            swapWithinNode(nodeIndex);
            repair(other);
            repair(entry);
            return;
        }
        if (entry.high && compare(entry.item, node.low.item) < 0) {
            Entry<T> other = node.low;
            swapWithinNode(nodeIndex);
            repair(other);
            repair(entry);
            return;
        }

        if (!entry.high) {
            if (nodeIndex > 0) {
                int parentIndex = (nodeIndex - 1) / 2;
                if (compare(entry.item, nodes[parentIndex].low.item) < 0) {
                    swapSlots(entry, false, parentIndex, false);
                    repair(entry);
                    return;
                }
            }

            int left = nodeIndex * 2 + 1;
            if (left < (size + 1) / 2) {
                int right = left + 1;
                int child = left;
                if (right < (size + 1) / 2
                        && compare(nodes[right].low.item, nodes[left].low.item) < 0) {
                    child = right;
                }
                if (compare(nodes[child].low.item, entry.item) < 0) {
                    swapSlots(entry, false, child, false);
                    repair(entry);
                }
            }
        } else {
            if (nodeIndex > 0) {
                int parentIndex = (nodeIndex - 1) / 2;
                if (compare(entry.item, nodes[parentIndex].high.item) > 0) {
                    swapSlots(entry, true, parentIndex, true);
                    repair(entry);
                    return;
                }
            }

            int left = nodeIndex * 2 + 1;
            int nodeCount = (size + 1) / 2;
            if (left < nodeCount) {
                int right = left + 1;
                int child = left;
                Entry<T> leftHigh = nodes[left].high == null
                        ? nodes[left].low : nodes[left].high;
                if (right < nodeCount) {
                    Entry<T> rightHigh = nodes[right].high == null
                            ? nodes[right].low : nodes[right].high;
                    if (compare(rightHigh.item, leftHigh.item) > 0) {
                        child = right;
                    }
                }
                Entry<T> childHigh = nodes[child].high == null
                        ? nodes[child].low : nodes[child].high;
                if (compare(childHigh.item, entry.item) > 0) {
                    swapSlots(entry, true, child, nodes[child].high != null);
                    repair(entry);
                }
            }
        }
    }

    private Entry<T> removeRoot(boolean high) {
        Node<T> root = nodes[0];
        Entry<T> removed = high && root.high != null ? root.high : root.low;
        int oldSize = size;

        if (oldSize == 1) {
            nodes[0] = null;
            size = 0;
            removed.alive = false;
            return removed;
        }

        int lastNodeIndex = (oldSize - 1) / 2;
        Node<T> lastNode = nodes[lastNodeIndex];
        boolean lastHigh = (oldSize & 1) == 0;
        Entry<T> last = lastHigh ? lastNode.high : lastNode.low;

        if (lastHigh) {
            lastNode.high = null;
        } else {
            nodes[lastNodeIndex] = null;
        }

        size--;
        if (last != removed) {
            if (high && root.high != null) {
                root.high = last;
                setLocation(last, 0, true);
            } else {
                root.low = last;
                setLocation(last, 0, false);
            }
            repair(last);
        }

        removed.alive = false;
        return removed;
    }
}

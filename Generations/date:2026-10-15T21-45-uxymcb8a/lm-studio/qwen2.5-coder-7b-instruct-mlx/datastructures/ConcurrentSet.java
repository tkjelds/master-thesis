@ConcurrentSet.ThreadSafe
public class ConcurrentSet<T> {
    @interface ThreadSafe {}

    private final Node<T>[] buckets;
    private static final int INITIAL_CAPACITY = 16;

    @SuppressWarnings("unchecked")
    public ConcurrentSet() {
        buckets = (Node<T>[]) new Node[INITIAL_CAPACITY];
    }

    private static class Node<T> {
        T value;
        volatile Node<T> next;

        Node(T value) {
            this.value = value;
        }
    }

    public boolean add(T value) {
        int index = hash(value);
        synchronized (buckets[index]) {
            Node<T> current = buckets[index];
            while (current != null) {
                if (current.value.equals(value)) {
                    return false; // Value already present
                }
                current = current.next;
            }
            Node<T> newNode = new Node<>(value);
            newNode.next = buckets[index];
            buckets[index] = newNode;
            return true;
        }
    }

    public boolean remove(T value) {
        int index = hash(value);
        synchronized (buckets[index]) {
            Node<T> current = buckets[index];
            Node<T> previous = null;
            while (current != null) {
                if (current.value.equals(value)) {
                    if (previous == null) {
                        buckets[index] = current.next;
                    } else {
                        previous.next = current.next;
                    }
                    return true;
                }
                previous = current;
                current = current.next;
            }
            return false; // Value not found
        }
    }

    public boolean contains(T value) {
        int index = hash(value);
        synchronized (buckets[index]) {
            Node<T> current = buckets[index];
            while (current != null) {
                if (current.value.equals(value)) {
                    return true;
                }
                current = current.next;
            }
            return false; // Value not found
        }
    }

    private int hash(T value) {
        return Math.abs(value.hashCode()) % buckets.length;
    }

    public int size() {
        int count = 0;
        for (Node<T> bucket : buckets) {
            synchronized (bucket) {
                Node<T> current = bucket;
                while (current != null) {
                    count++;
                    current = current.next;
                }
            }
        }
        return count;
    }

    public void clear() {
        for (Node<T> bucket : buckets) {
            synchronized (bucket) {
                bucket.next = null;
            }
        }
    }
}

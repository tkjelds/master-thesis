@ConcurrentDictionary.ThreadSafe
public class ConcurrentDictionary {
    @interface ThreadSafe {}

    private final Node[] buckets;
    private final int capacity;

    public ConcurrentDictionary(int capacity) {
        this.capacity = capacity;
        this.buckets = new Node[capacity];
        for (int i = 0; i < capacity; i++) {
            buckets[i] = new Node();
        }
    }

    public boolean put(String key, String value) {
        int index = hash(key);
        synchronized (buckets[index]) {
            Node node = buckets[index];
            while (node.next != null && !node.key.equals(key)) {
                node = node.next;
            }
            if (node.key.equals(key)) {
                return false; // already exists
            } else {
                node.next = new Node(key, value);
                return true;
            }
        }
    }

    public String get(String key) {
        int index = hash(key);
        synchronized (buckets[index]) {
            Node node = buckets[index];
            while (node != null) {
                if (node.key.equals(key)) {
                    return node.value;
                }
                node = node.next;
            }
            return null; // not found
        }
    }

    public boolean remove(String key) {
        int index = hash(key);
        synchronized (buckets[index]) {
            Node node = buckets[index];
            Node prev = null;
            while (node != null) {
                if (node.key.equals(key)) {
                    if (prev == null) {
                        buckets[index] = node.next;
                    } else {
                        prev.next = node.next;
                    }
                    return true;
                }
                prev = node;
                node = node.next;
            }
            return false; // not found
        }
    }

    private int hash(String key) {
        return Math.abs(key.hashCode()) % capacity;
    }

    private static class Node {
        final String key;
        String value;
        volatile Node next;

        Node() {}

        Node(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }
}

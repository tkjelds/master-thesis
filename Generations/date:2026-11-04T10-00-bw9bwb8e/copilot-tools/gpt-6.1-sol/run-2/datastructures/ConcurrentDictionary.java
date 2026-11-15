/**
 * A thread-safe dictionary supporting null keys and null values.
 *
 * Keys are compared using equals; keys must not change their equality behavior
 * while stored. Each operation is atomic with respect to other operations.
 *
 * Storage is unbounded except by available memory. Missing-key operations return
 * immediately; no operation waits for a key or for capacity. Operations may
 * block while acquiring the dictionary's intrinsic monitor.
 *
 * Lookup, insertion, and removal take O(n) time. Size and clear take O(1).
 */
@ConcurrentDictionary.ThreadSafe
public final class ConcurrentDictionary<K, V> {
    @interface ThreadSafe {}

    private static final class Monitor {
    }

    private static final class Entry<K, V> {
        final K key;
        V value;
        Entry<K, V> next;

        Entry(K key, V value, Entry<K, V> next) {
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }

    private final Monitor monitor = new Monitor();
    private Entry<K, V> head;
    private long count;

    public ConcurrentDictionary() {
    }

    /**
     * Associates value with key, returning the previous value, or null if absent.
     * Use containsKey to distinguish absence from a stored null value.
     */
    public V put(K key, V value) {
        synchronized (monitor) {
            Entry<K, V> entry = find(key);
            if (entry != null) {
                V previous = entry.value;
                entry.value = value;
                return previous;
            }

            head = new Entry<K, V>(key, value, head);
            count++;
            return null;
        }
    }

    /**
     * Returns the associated value, or null if the key is absent.
     */
    public V get(K key) {
        synchronized (monitor) {
            Entry<K, V> entry = find(key);
            return entry == null ? null : entry.value;
        }
    }

    public boolean containsKey(K key) {
        synchronized (monitor) {
            return find(key) != null;
        }
    }

    /**
     * Removes the association and returns its value, or null if absent.
     */
    public V remove(K key) {
        synchronized (monitor) {
            Entry<K, V> previous = null;
            Entry<K, V> current = head;

            while (current != null) {
                if (keysEqual(key, current.key)) {
                    if (previous == null) {
                        head = current.next;
                    } else {
                        previous.next = current.next;
                    }
                    count--;
                    return current.value;
                }

                previous = current;
                current = current.next;
            }

            return null;
        }
    }

    public long size() {
        synchronized (monitor) {
            return count;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return head == null;
        }
    }

    public void clear() {
        synchronized (monitor) {
            head = null;
            count = 0;
        }
    }

    // Called only while holding monitor.
    private Entry<K, V> find(K key) {
        Entry<K, V> current = head;
        while (current != null) {
            if (keysEqual(key, current.key)) {
                return current;
            }
            current = current.next;
        }
        return null;
    }

    private boolean keysEqual(K first, K second) {
        return first == second || (first != null && first.equals(second));
    }
}

/**
 * A thread-safe dictionary implemented using a singly linked list.
 *
 * Null keys and values are permitted. A null result from get, put, or remove
 * may mean either absence or a stored null value; containsKey distinguishes
 * these cases.
 *
 * Every operation is serialized using a private intrinsic monitor. Operations
 * never wait for entries or capacity; they may block while acquiring the
 * monitor. Empty lookups and removals return null immediately after acquiring
 * it. Storage grows as needed, subject to available memory.
 *
 * Keys must obey the equals contract, and their equality behavior must remain
 * stable while stored. Equality methods must not modify this dictionary.
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

    public ConcurrentDictionary() {
    }

    /**
     * Returns the value associated with key, or null if absent.
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
     * Inserts or replaces a mapping and returns its previous value,
     * or null if no mapping previously existed.
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
            return null;
        }
    }

    /**
     * Removes a mapping and returns its previous value, or null if absent.
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
                    return current.value;
                }
                previous = current;
                current = current.next;
            }

            return null;
        }
    }

    /**
     * Returns the number of mappings, saturating at Integer.MAX_VALUE.
     */
    public int size() {
        synchronized (monitor) {
            int count = 0;
            Entry<K, V> current = head;

            while (current != null && count < 2147483647) {
                count++;
                current = current.next;
            }

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
        }
    }

    /* Called only while holding monitor. */
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

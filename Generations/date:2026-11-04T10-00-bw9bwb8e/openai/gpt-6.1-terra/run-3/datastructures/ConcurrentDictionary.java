/**
 * A thread-safe, unbounded-by-configuration dictionary backed by a linked list.
 *
 * Null keys and values are supported. A null result from get, put, or remove
 * may represent either an absent key or a stored null value; use containsKey
 * when that distinction matters.
 *
 * Operations wait only to acquire the internal monitor. They do not wait for
 * keys to appear or capacity to become available. If the number of entries
 * reaches Integer.MAX_VALUE, inserting a new key throws IllegalStateException;
 * updating an existing key remains allowed.
 *
 * Keys are compared using equals. Their equality behavior must remain stable
 * while stored, and equals must not modify this dictionary. Mutable keys and
 * values require their own synchronization when accessed outside this class.
 */
@ConcurrentDictionary.ThreadSafe
public final class ConcurrentDictionary<K, V> {
    @interface ThreadSafe {}

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

    private final Object lock = new Object();
    private Entry<K, V> head;
    private int size;

    private static boolean keysEqual(Object first, Object second) {
        return first == second || (first != null && first.equals(second));
    }

    public V get(K key) {
        synchronized (lock) {
            for (Entry<K, V> entry = head; entry != null; entry = entry.next) {
                if (keysEqual(key, entry.key)) {
                    return entry.value;
                }
            }
            return null;
        }
    }

    public boolean containsKey(K key) {
        synchronized (lock) {
            for (Entry<K, V> entry = head; entry != null; entry = entry.next) {
                if (keysEqual(key, entry.key)) {
                    return true;
                }
            }
            return false;
        }
    }

    public V put(K key, V value) {
        synchronized (lock) {
            for (Entry<K, V> entry = head; entry != null; entry = entry.next) {
                if (keysEqual(key, entry.key)) {
                    V previous = entry.value;
                    entry.value = value;
                    return previous;
                }
            }

            if (size == 2147483647) {
                throw new IllegalStateException("Dictionary is full");
            }

            head = new Entry<K, V>(key, value, head);
            size++;
            return null;
        }
    }

    public V remove(K key) {
        synchronized (lock) {
            Entry<K, V> previous = null;
            Entry<K, V> current = head;

            while (current != null) {
                if (keysEqual(key, current.key)) {
                    if (previous == null) {
                        head = current.next;
                    } else {
                        previous.next = current.next;
                    }
                    size--;
                    return current.value;
                }
                previous = current;
                current = current.next;
            }
            return null;
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    public void clear() {
        synchronized (lock) {
            head = null;
            size = 0;
        }
    }
}

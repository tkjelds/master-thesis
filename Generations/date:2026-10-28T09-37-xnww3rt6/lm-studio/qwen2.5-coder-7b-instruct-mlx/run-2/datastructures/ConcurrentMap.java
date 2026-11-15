@ConcurrentMap.ThreadSafe
public class ConcurrentMap<K, V> {
    @interface ThreadSafe {}

    private final Object[] locks;
    private final Entry<K, V>[] entries;

    @SuppressWarnings("unchecked")
    public ConcurrentMap(int capacity) {
        locks = new Object[capacity];
        entries = new Entry[capacity];
        for (int i = 0; i < capacity; i++) {
            locks[i] = new Object();
            entries[i] = null;
        }
    }

    public V get(K key) {
        if (key == null) throw new NullPointerException();
        int index = Math.abs(key.hashCode()) % entries.length;
        synchronized (locks[index]) {
            Entry<K, V> entry = entries[index];
            while (entry != null) {
                if (entry.key.equals(key)) {
                    return entry.value;
                }
                entry = entry.next;
            }
        }
        return null;
    }

    public V put(K key, V value) {
        if (key == null || value == null) throw new NullPointerException();
        int index = Math.abs(key.hashCode()) % entries.length;
        synchronized (locks[index]) {
            Entry<K, V> current = entries[index];
            while (current != null) {
                if (current.key.equals(key)) {
                    V oldValue = current.value;
                    current.value = value;
                    return oldValue;
                }
                current = current.next;
            }
            entries[index] = new Entry<>(key, value, entries[index]);
        }
        return null;
    }

    public V remove(K key) {
        if (key == null) throw new NullPointerException();
        int index = Math.abs(key.hashCode()) % entries.length;
        synchronized (locks[index]) {
            Entry<K, V> current = entries[index];
            Entry<K, V> prev = null;
            while (current != null) {
                if (current.key.equals(key)) {
                    V value = current.value;
                    if (prev == null) {
                        entries[index] = current.next;
                    } else {
                        prev.next = current.next;
                    }
                    return value;
                }
                prev = current;
                current = current.next;
            }
        }
        return null;
    }

    private static class Entry<K, V> {
        final K key;
        V value;
        Entry<K, V> next;

        public Entry(K key, V value, Entry<K, V> next) {
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.length; i++) {
            synchronized (locks[i]) {
                Entry<K, V> current = entries[i];
                while (current != null) {
                    sb.append(current.key).append("=").append(current.value).append("; ");
                    current = current.next;
                }
            }
        }
        return sb.toString();
    }
}

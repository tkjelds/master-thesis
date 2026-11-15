@ConcurrentMap.ThreadSafe
public class ConcurrentMap<K, V> {
    @interface ThreadSafe {}

    private final Entry<K, V>[] table;
    private static final int INITIAL_CAPACITY = 16;

    @SuppressWarnings("unchecked")
    public ConcurrentMap() {
        table = (Entry<K, V>[]) new Entry[INITIAL_CAPACITY];
    }

    public synchronized void put(K key, V value) {
        int index = hash(key);
        Entry<K, V> entry = table[index];
        if (entry == null) {
            table[index] = new Entry<>(key, value);
        } else {
            if (entry.key.equals(key)) {
                entry.value = value;
            } else {
                while (entry.next != null) {
                    if (entry.next.key.equals(key)) {
                        entry.next.value = value;
                        return;
                    }
                    entry = entry.next;
                }
                entry.next = new Entry<>(key, value);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public synchronized V get(K key) {
        int index = hash(key);
        Entry<K, V> entry = table[index];
        while (entry != null) {
            if (entry.key.equals(key)) {
                return entry.value;
            }
            entry = entry.next;
        }
        return null;
    }

    public synchronized V remove(K key) {
        int index = hash(key);
        Entry<K, V> entry = table[index];
        if (entry != null) {
            if (entry.key.equals(key)) {
                V oldValue = entry.value;
                table[index] = entry.next;
                return oldValue;
            } else {
                while (entry.next != null) {
                    if (entry.next.key.equals(key)) {
                        V oldValue = entry.next.value;
                        entry.next = entry.next.next;
                        return oldValue;
                    }
                    entry = entry.next;
                }
            }
        }
        return null;
    }

    private int hash(K key) {
        return Math.abs(key.hashCode()) % table.length;
    }

    private static class Entry<K, V> {
        final K key;
        V value;
        Entry<K, V> next;

        Entry(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }
}

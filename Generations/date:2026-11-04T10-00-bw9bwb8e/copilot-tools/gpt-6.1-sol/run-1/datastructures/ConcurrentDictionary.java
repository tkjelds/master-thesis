/**
 * A thread-safe dictionary implemented using a separate-chaining hash table.
 *
 * Null keys and values are not supported. Missing keys return null from get
 * and remove. put returns the previous value, or null for a new key.
 *
 * Operations wait only for the dictionary's intrinsic monitor. Storage grows
 * automatically; operations never wait for entries or available capacity.
 *
 * Keys must have stable hashCode and equals implementations while stored.
 * Those implementations must not modify this dictionary.
 */
@ConcurrentDictionary.ThreadSafe
public final class ConcurrentDictionary<K, V> {
    @interface ThreadSafe {}

    private static final class Entry {
        final int hash;
        final Object key;
        Object value;
        Entry next;

        Entry(int hash, Object key, Object value, Entry next) {
            this.hash = hash;
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }

    private static final class State {
        Entry[] buckets = new Entry[16];
        int size;
    }

    // Final publication also protects the initially reachable table.
    private final State state = new State();

    public ConcurrentDictionary() {
    }

    public V get(K key) {
        requireKey(key);
        synchronized (state) {
            Entry entry = find(key, hash(key));
            return entry == null ? null : valueOf(entry);
        }
    }

    public boolean containsKey(K key) {
        requireKey(key);
        synchronized (state) {
            return find(key, hash(key)) != null;
        }
    }

    public V put(K key, V value) {
        requireKey(key);
        if (value == null) {
            throw new NullPointerException("Null values are not supported");
        }

        synchronized (state) {
            int hash = hash(key);
            Entry existing = find(key, hash);
            if (existing != null) {
                V previous = valueOf(existing);
                existing.value = value;
                return previous;
            }

            if (state.size == Integer.MAX_VALUE) {
                throw new IllegalStateException("Dictionary size limit reached");
            }

            // Allocate before changing the table so allocation failure cannot
            // leave a partially inserted entry.
            Entry entry = new Entry(hash, key, value, null);
            if (state.size >= state.buckets.length
                    && state.buckets.length < (1 << 30)) {
                grow();
            }

            int index = hash & (state.buckets.length - 1);
            entry.next = state.buckets[index];
            state.buckets[index] = entry;
            state.size++;
            return null;
        }
    }

    public V remove(K key) {
        requireKey(key);
        synchronized (state) {
            int hash = hash(key);
            int index = hash & (state.buckets.length - 1);
            Entry previous = null;
            Entry entry = state.buckets[index];

            while (entry != null) {
                if (matches(entry, key, hash)) {
                    if (previous == null) {
                        state.buckets[index] = entry.next;
                    } else {
                        previous.next = entry.next;
                    }
                    state.size--;
                    return valueOf(entry);
                }
                previous = entry;
                entry = entry.next;
            }
            return null;
        }
    }

    public int size() {
        synchronized (state) {
            return state.size;
        }
    }

    public boolean isEmpty() {
        synchronized (state) {
            return state.size == 0;
        }
    }

    public void clear() {
        synchronized (state) {
            for (int i = 0; i < state.buckets.length; i++) {
                state.buckets[i] = null;
            }
            state.size = 0;
        }
    }

    private static void requireKey(Object key) {
        if (key == null) {
            throw new NullPointerException("Null keys are not supported");
        }
    }

    private static int hash(Object key) {
        int hash = key.hashCode();
        return hash ^ (hash >>> 16);
    }

    private static boolean matches(Entry entry, Object key, int hash) {
        return entry.hash == hash
                && (entry.key == key || key.equals(entry.key));
    }

    // Called only while holding state's monitor.
    private Entry find(Object key, int hash) {
        int index = hash & (state.buckets.length - 1);
        Entry entry = state.buckets[index];
        while (entry != null) {
            if (matches(entry, key, hash)) {
                return entry;
            }
            entry = entry.next;
        }
        return null;
    }

    // Called only while holding state's monitor.
    private void grow() {
        Entry[] oldBuckets = state.buckets;
        Entry[] newBuckets = new Entry[oldBuckets.length * 2];

        for (int i = 0; i < oldBuckets.length; i++) {
            Entry entry = oldBuckets[i];
            while (entry != null) {
                Entry next = entry.next;
                int index = entry.hash & (newBuckets.length - 1);
                entry.next = newBuckets[index];
                newBuckets[index] = entry;
                entry = next;
            }
        }
        state.buckets = newBuckets;
    }

    @SuppressWarnings("unchecked")
    private V valueOf(Entry entry) {
        return (V) entry.value;
    }
}

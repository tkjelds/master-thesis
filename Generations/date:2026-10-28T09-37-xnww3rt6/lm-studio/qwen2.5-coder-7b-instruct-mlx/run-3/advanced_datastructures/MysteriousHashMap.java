import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.function.BiConsumer;

@MysteriousHashMap.ThreadSafe
public class MysteriousHashMap<K, V> {
    @interface ThreadSafe {}

    private static final int INITIAL_CAPACITY = 16;
    private static final float LOAD_FACTOR = 0.75f;

    private Stripe[] stripes;
    private int size;

    public MysteriousHashMap() {
        this.stripes = new Stripe[INITIAL_CAPACITY];
        for (int i = 0; i < INITIAL_CAPACITY; i++) {
            stripes[i] = new Stripe();
        }
    }

    public boolean containsKey(K k) {
        int hash = hash(k);
        return getBucket(hash).containsKey(k);
    }

    public V get(K k) {
        int hash = hash(k);
        return getBucket(hash).get(k);
    }

    public V put(K k, V v) {
        int hash = hash(k);
        return putHelper(hash, k, v);
    }

    public V remove(K k) {
        int hash = hash(k);
        return removeHelper(hash, k);
    }

    public int size() {
        return size;
    }

    public void forEach(BiConsumer<K, V> consumer) {
        for (int i = 0; i < stripes.length; i++) {
            synchronized (stripes[i]) {
                for (SimpleImmutableEntry<K, V> entry : stripes[i].getBucket()) {
                    consumer.accept(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    private int hash(K k) {
        return k.hashCode() & (stripes.length - 1);
    }

    private SimpleImmutableEntry<K, V> putHelper(int hash, K k, V v) {
        synchronized (stripes[hash]) {
            SimpleImmutableEntry<K, V> oldValue = getBucket(hash).put(k, v);
            if (oldValue == null) {
                growIfNecessary();
            }
            return oldValue;
        }
    }

    private V removeHelper(int hash, K k) {
        synchronized (stripes[hash]) {
            V oldValue = getBucket(hash).remove(k);
            if (oldValue != null) {
                size--;
            }
            return oldValue;
        }
    }

    private boolean growIfNecessary() {
        int threshold = (int) (stripes.length * LOAD_FACTOR);
        if (size >= threshold) {
            synchronized (this) {
                if (size >= threshold) {
                    Stripe[] newStripes = new Stripe[stripes.length * 2];
                    for (int i = 0; i < stripes.length; i++) {
                        synchronized (stripes[i]) {
                            newStripes[i] = stripes[i].copy();
                            newStripes[newStripes.length - 1 - i] = stripes[i].copy();
                        }
                    }
                    stripes = newStripes;
                }
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private Bucket<K, V> getBucket(int hash) {
        return (Bucket<K, V>) stripes[hash].getBucket();
    }

    private static class Stripe {
        private volatile Bucket bucket;

        public Stripe() {
            this.bucket = new Bucket();
        }

        public synchronized Bucket copy() {
            return bucket.copy();
        }

        @SuppressWarnings("unchecked")
        public Bucket getBucket() {
            return (Bucket) bucket;
        }
    }

    private static class Bucket<K, V> {
        private final SimpleImmutableEntry<K, V>[] entries;

        @SuppressWarnings("unchecked")
        public Bucket() {
            this.entries = new SimpleImmutableEntry[16];
        }

        @SuppressWarnings("unchecked")
        public synchronized SimpleImmutableEntry<K, V> put(K k, V v) {
            int index = findIndex(k);
            SimpleImmutableEntry<K, V> oldValue = null;
            if (index >= 0) {
                oldValue = entries[index];
            } else {
                index = -index - 1;
                if (entries.length == size()) {
                    entries = resize();
                    index = -index - 1;
                }
            }
            entries[index] = new SimpleImmutableEntry<>(k, v);
            return oldValue;
        }

        @SuppressWarnings("unchecked")
        public synchronized V remove(K k) {
            int index = findIndex(k);
            if (index >= 0) {
                V oldValue = entries[index].getValue();
                System.arraycopy(entries, index + 1, entries, index, size() - index - 1);
                entries[size() - 1] = null;
                return oldValue;
            }
            return null;
        }

        @SuppressWarnings("unchecked")
        public synchronized boolean containsKey(K k) {
            return findIndex(k) >= 0;
        }

        @SuppressWarnings("unchecked")
        public synchronized V get(K k) {
            int index = findIndex(k);
            return index >= 0 ? entries[index].getValue() : null;
        }

        private int findIndex(K k) {
            for (int i = 0; i < size(); i++) {
                if (entries[i].getKey().equals(k)) {
                    return i;
                }
            }
            return ~size();
        }

        private SimpleImmutableEntry<K, V>[] resize() {
            int newSize = entries.length * 2;
            SimpleImmutableEntry<K, V>[] newEntries = new SimpleImmutableEntry[newSize];
            System.arraycopy(entries, 0, newEntries, 0, entries.length);
            return newEntries;
        }

        @SuppressWarnings("unchecked")
        public synchronized int size() {
            for (int i = 0; i < entries.length; i++) {
                if (entries[i] == null) {
                    return i;
                }
            }
            return entries.length;
        }

        @SuppressWarnings("unchecked")
        public synchronized SimpleImmutableEntry<K, V>[] copy() {
            return entries.clone();
        }
    }
}

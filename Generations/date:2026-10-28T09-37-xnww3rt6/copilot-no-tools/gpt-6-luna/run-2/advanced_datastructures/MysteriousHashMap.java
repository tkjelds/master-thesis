@MysteriousHashMap.ThreadSafe
public class MysteriousHashMap<K, V> {
    @interface ThreadSafe {}

    public interface BiConsumer<K, V> {
        void accept(K key, V value);
    }

    private static final int STRIPE_COUNT = 16;
    private static final int INITIAL_BUCKET_COUNT = 16;
    private static final int MAXIMUM_CAPACITY = 1 << 30;

    private final Stripe<K, V>[] stripes;

    public MysteriousHashMap() {
        stripes = newStripeArray(STRIPE_COUNT);
        for (int i = 0; i < stripes.length; i++) {
            stripes[i] = new Stripe<K, V>(INITIAL_BUCKET_COUNT);
        }
    }

    public boolean containsKey(K key) {
        requireKey(key);
        int hash = spread(key.hashCode());
        Stripe<K, V> stripe = stripes[hash & (STRIPE_COUNT - 1)];
        Bucket<K, V>[] table = stripe.buckets;
        return findEntry(table, hash, key) != null;
    }

    public V get(K key) {
        requireKey(key);
        int hash = spread(key.hashCode());
        Stripe<K, V> stripe = stripes[hash & (STRIPE_COUNT - 1)];
        Bucket<K, V>[] table = stripe.buckets;
        Entry<K, V> entry = findEntry(table, hash, key);
        return entry == null ? null : entry.value;
    }

    public V put(K key, V value) {
        requireKey(key);
        requireValue(value);

        int hash = spread(key.hashCode());
        Stripe<K, V> stripe = stripes[hash & (STRIPE_COUNT - 1)];
        synchronized (stripe) {
            Bucket<K, V>[] table = stripe.buckets;
            int bucketIndex = hash & (table.length - 1);
            Bucket<K, V> bucket = table[bucketIndex];
            Entry<K, V> oldEntry = findEntry(bucket.head, key);

            if (oldEntry != null) {
                bucket.head = replaceEntry(bucket.head, oldEntry, value);
                return oldEntry.value;
            }

            bucket.head = new Entry<K, V>(key, value, bucket.head);
            stripe.size++;

            if (stripe.size > table.length - (table.length >>> 2)
                    && table.length < MAXIMUM_CAPACITY) {
                resize(stripe, table);
            }
            return null;
        }
    }

    public V remove(K key) {
        requireKey(key);
        int hash = spread(key.hashCode());
        Stripe<K, V> stripe = stripes[hash & (STRIPE_COUNT - 1)];
        synchronized (stripe) {
            Bucket<K, V>[] table = stripe.buckets;
            int bucketIndex = hash & (table.length - 1);
            Bucket<K, V> bucket = table[bucketIndex];
            Entry<K, V> oldEntry = findEntry(bucket.head, key);

            if (oldEntry == null) {
                return null;
            }

            bucket.head = removeEntry(bucket.head, oldEntry);
            stripe.size--;
            return oldEntry.value;
        }
    }

    public int size() {
        return sumSizes(0, 0);
    }

    public void forEach(BiConsumer<K, V> consumer) {
        if (consumer == null) {
            throw new NullPointerException();
        }

        for (Stripe<K, V> stripe : stripes) {
            Bucket<K, V>[] table = stripe.buckets;
            for (Bucket<K, V> bucket : table) {
                for (Entry<K, V> entry = bucket.head; entry != null; entry = entry.next) {
                    consumer.accept(entry.key, entry.value);
                }
            }
        }
    }

    private int sumSizes(int index, int total) {
        if (index == stripes.length) {
            return total;
        }
        Stripe<K, V> stripe = stripes[index];
        synchronized (stripe) {
            return sumSizes(index + 1, total + stripe.size);
        }
    }

    private static int spread(int hash) {
        return hash ^ (hash >>> 16);
    }

    private static void requireKey(Object key) {
        if (key == null) {
            throw new NullPointerException();
        }
    }

    private static void requireValue(Object value) {
        if (value == null) {
            throw new NullPointerException();
        }
    }

    private static <K, V> Entry<K, V> findEntry(
            Bucket<K, V>[] table, int hash, K key) {
        return findEntry(table[hash & (table.length - 1)].head, key);
    }

    private static <K, V> Entry<K, V> findEntry(Entry<K, V> head, K key) {
        for (Entry<K, V> entry = head; entry != null; entry = entry.next) {
            if (entry.key == key || key.equals(entry.key)) {
                return entry;
            }
        }
        return null;
    }

    private static <K, V> Entry<K, V> replaceEntry(
            Entry<K, V> head, Entry<K, V> target, V value) {
        Entry<K, V> prefixReversed = null;
        Entry<K, V> current = head;

        while (current != target) {
            prefixReversed = new Entry<K, V>(
                    current.key, current.value, prefixReversed);
            current = current.next;
        }

        Entry<K, V> result = new Entry<K, V>(
                target.key, value, target.next);
        while (prefixReversed != null) {
            result = new Entry<K, V>(
                    prefixReversed.key, prefixReversed.value, result);
            prefixReversed = prefixReversed.next;
        }
        return result;
    }

    private static <K, V> Entry<K, V> removeEntry(
            Entry<K, V> head, Entry<K, V> target) {
        Entry<K, V> prefixReversed = null;
        Entry<K, V> current = head;

        while (current != target) {
            prefixReversed = new Entry<K, V>(
                    current.key, current.value, prefixReversed);
            current = current.next;
        }

        Entry<K, V> result = target.next;
        while (prefixReversed != null) {
            result = new Entry<K, V>(
                    prefixReversed.key, prefixReversed.value, result);
            prefixReversed = prefixReversed.next;
        }
        return result;
    }

    private static <K, V> void resize(
            Stripe<K, V> stripe, Bucket<K, V>[] oldTable) {
        int newCapacity = oldTable.length << 1;
        Bucket<K, V>[] newTable = newBucketArray(newCapacity);

        for (int i = 0; i < newCapacity; i++) {
            newTable[i] = new Bucket<K, V>();
        }

        for (Bucket<K, V> bucket : oldTable) {
            for (Entry<K, V> entry = bucket.head;
                    entry != null;
                    entry = entry.next) {
                int index = spread(entry.key.hashCode()) & (newCapacity - 1);
                newTable[index].head = new Entry<K, V>(
                        entry.key, entry.value, newTable[index].head);
            }
        }

        stripe.buckets = newTable;
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Stripe<K, V>[] newStripeArray(int length) {
        return (Stripe<K, V>[]) new Stripe[length];
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Bucket<K, V>[] newBucketArray(int length) {
        return (Bucket<K, V>[]) new Bucket[length];
    }

    private static final class Stripe<K, V> {
        private volatile Bucket<K, V>[] buckets;
        private int size;

        private Stripe(int bucketCount) {
            buckets = newBucketArray(bucketCount);
            for (int i = 0; i < bucketCount; i++) {
                buckets[i] = new Bucket<K, V>();
            }
        }
    }

    private static final class Bucket<K, V> {
        private volatile Entry<K, V> head;
    }

    private static final class Entry<K, V> {
        private final K key;
        private final V value;
        private final Entry<K, V> next;

        private Entry(K key, V value, Entry<K, V> next) {
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }
}

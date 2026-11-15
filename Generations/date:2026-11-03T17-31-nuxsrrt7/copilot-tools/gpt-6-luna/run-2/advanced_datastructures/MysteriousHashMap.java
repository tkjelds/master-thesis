@MysteriousHashMap.ThreadSafe
public class MysteriousHashMap<K, V> {
    @interface ThreadSafe {}

    private static final int STRIPE_COUNT = 16;
    private static final int STRIPE_SHIFT = 4;
    private static final int INITIAL_CAPACITY = 16;
    private static final int MAX_CAPACITY = 1 << 30;

    private final Stripe<K, V>[] stripes;

    @SuppressWarnings("unchecked")
    public MysteriousHashMap() {
        stripes = (Stripe<K, V>[]) new Stripe[STRIPE_COUNT];
        for (int i = 0; i < STRIPE_COUNT; i++) {
            stripes[i] = new Stripe<K, V>(INITIAL_CAPACITY);
        }
    }

    public boolean containsKey(K key) {
        requireKey(key);
        int hash = spread(key.hashCode());
        Stripe<K, V> stripe = stripes[stripeIndex(hash)];
        Entry<K, V>[] buckets = stripe.buckets;
        for (Entry<K, V> entry = buckets[bucketIndex(hash, buckets.length)];
             entry != null; entry = entry.next) {
            if (entry.key.equals(key)) {
                return true;
            }
        }
        return false;
    }

    public V get(K key) {
        requireKey(key);
        int hash = spread(key.hashCode());
        Stripe<K, V> stripe = stripes[stripeIndex(hash)];
        Entry<K, V>[] buckets = stripe.buckets;
        for (Entry<K, V> entry = buckets[bucketIndex(hash, buckets.length)];
             entry != null; entry = entry.next) {
            if (entry.key.equals(key)) {
                return entry.value;
            }
        }
        return null;
    }

    public V put(K key, V value) {
        requireKey(key);
        if (value == null) {
            throw new NullPointerException();
        }

        int hash = spread(key.hashCode());
        int stripeIndex = stripeIndex(hash);
        Stripe<K, V> stripe = stripes[stripeIndex];

        synchronized (stripe) {
            Entry<K, V>[] oldBuckets = stripe.buckets;
            int index = bucketIndex(hash, oldBuckets.length);
            Entry<K, V> head = oldBuckets[index];
            int prefixLength = 0;

            for (Entry<K, V> entry = head; entry != null; entry = entry.next) {
                if (entry.key.equals(key)) {
                    @SuppressWarnings("unchecked")
                    Entry<K, V>[] prefix = (Entry<K, V>[]) new Entry[prefixLength];
                    Entry<K, V> current = head;
                    for (int i = 0; i < prefixLength; i++) {
                        prefix[i] = current;
                        current = current.next;
                    }

                    Entry<K, V> replacement =
                            new Entry<K, V>(current.key, value, current.next);
                    for (int i = prefixLength - 1; i >= 0; i--) {
                        replacement = new Entry<K, V>(
                                prefix[i].key, prefix[i].value, replacement);
                    }

                    Entry<K, V>[] newBuckets = copyTable(oldBuckets);
                    newBuckets[index] = replacement;
                    stripe.buckets = newBuckets;
                    return entry.value;
                }
                prefixLength++;
            }

            int newSize = stripe.size + 1;
            Entry<K, V>[] newBuckets = oldBuckets;
            if (oldBuckets.length < MAX_CAPACITY
                    && newSize > oldBuckets.length - (oldBuckets.length >>> 2)) {
                newBuckets = resize(oldBuckets, stripeIndex, oldBuckets.length << 1);
            } else {
                newBuckets = copyTable(oldBuckets);
            }

            int newIndex = bucketIndex(hash, newBuckets.length);
            newBuckets[newIndex] = new Entry<K, V>(key, value, newBuckets[newIndex]);
            stripe.buckets = newBuckets;
            stripe.size = newSize;
            return null;
        }
    }

    public V remove(K key) {
        requireKey(key);
        int hash = spread(key.hashCode());
        int stripeIndex = stripeIndex(hash);
        Stripe<K, V> stripe = stripes[stripeIndex];

        synchronized (stripe) {
            Entry<K, V>[] oldBuckets = stripe.buckets;
            int index = bucketIndex(hash, oldBuckets.length);
            Entry<K, V> head = oldBuckets[index];
            int prefixLength = 0;

            for (Entry<K, V> entry = head; entry != null; entry = entry.next) {
                if (entry.key.equals(key)) {
                    @SuppressWarnings("unchecked")
                    Entry<K, V>[] prefix = (Entry<K, V>[]) new Entry[prefixLength];
                    Entry<K, V> current = head;
                    for (int i = 0; i < prefixLength; i++) {
                        prefix[i] = current;
                        current = current.next;
                    }

                    Entry<K, V> replacement = current.next;
                    for (int i = prefixLength - 1; i >= 0; i--) {
                        replacement = new Entry<K, V>(
                                prefix[i].key, prefix[i].value, replacement);
                    }

                    Entry<K, V>[] newBuckets = copyTable(oldBuckets);
                    newBuckets[index] = replacement;
                    stripe.buckets = newBuckets;
                    stripe.size--;
                    return entry.value;
                }
                prefixLength++;
            }
            return null;
        }
    }

    public int size() {
        long total = sizeWithLocks(0);
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    public void forEach(java.util.function.BiConsumer<K, V> consumer) {
        if (consumer == null) {
            throw new NullPointerException();
        }

        for (Stripe<K, V> stripe : stripes) {
            Entry<K, V>[] buckets = stripe.buckets;
            for (Entry<K, V> head : buckets) {
                for (Entry<K, V> entry = head; entry != null; entry = entry.next) {
                    consumer.accept(entry.key, entry.value);
                }
            }
        }
    }

    private long sizeWithLocks(int stripeIndex) {
        if (stripeIndex == stripes.length) {
            return 0L;
        }
        Stripe<K, V> stripe = stripes[stripeIndex];
        synchronized (stripe) {
            return stripe.size + sizeWithLocks(stripeIndex + 1);
        }
    }

    private static void requireKey(Object key) {
        if (key == null) {
            throw new NullPointerException();
        }
    }

    private static int spread(int hash) {
        return hash ^ (hash >>> 16);
    }

    private static int stripeIndex(int hash) {
        return hash & (STRIPE_COUNT - 1);
    }

    private static int bucketIndex(int hash, int capacity) {
        return (hash >>> STRIPE_SHIFT) & (capacity - 1);
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Entry<K, V>[] newTable(int capacity) {
        return (Entry<K, V>[]) new Entry[capacity];
    }

    private static <K, V> Entry<K, V>[] copyTable(Entry<K, V>[] source) {
        Entry<K, V>[] copy = newTable(source.length);
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }

    private static <K, V> Entry<K, V>[] resize(
            Entry<K, V>[] oldBuckets, int stripeIndex, int newCapacity) {
        Entry<K, V>[] newBuckets = newTable(newCapacity);
        for (Entry<K, V> head : oldBuckets) {
            for (Entry<K, V> entry = head; entry != null; entry = entry.next) {
                int hash = spread(entry.key.hashCode());
                int index = bucketIndex(hash, newCapacity);
                newBuckets[index] = new Entry<K, V>(
                        entry.key, entry.value, newBuckets[index]);
            }
        }
        return newBuckets;
    }

    private static final class Stripe<K, V> {
        private volatile Entry<K, V>[] buckets;
        private int size;

        private Stripe(int capacity) {
            buckets = newTable(capacity);
        }
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

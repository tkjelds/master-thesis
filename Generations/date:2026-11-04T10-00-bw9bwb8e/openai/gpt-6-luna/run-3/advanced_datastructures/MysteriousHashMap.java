@MysteriousHashMap.ThreadSafe
public class MysteriousHashMap<K, V> {
    @interface ThreadSafe {}

    private static final int DEFAULT_STRIPE_COUNT = 16;
    private static final int DEFAULT_BUCKET_COUNT = 16;

    private final Stripe<K, V>[] stripes;
    private final int stripeMask;
    private final int stripeShift;

    @SuppressWarnings("unchecked")
    public MysteriousHashMap() {
        this.stripes = (Stripe<K, V>[]) new Stripe[DEFAULT_STRIPE_COUNT];
        this.stripeMask = DEFAULT_STRIPE_COUNT - 1;

        int shift = 0;
        for (int n = DEFAULT_STRIPE_COUNT; n > 1; n >>>= 1) {
            shift++;
        }
        this.stripeShift = shift;

        for (int i = 0; i < stripes.length; i++) {
            stripes[i] = new Stripe<K, V>(newEntryArray(DEFAULT_BUCKET_COUNT));
        }
    }

    public boolean containsKey(K k) {
        requireNonNull(k);
        int hash = spreadHash(k.hashCode());
        Stripe<K, V> stripe = stripeFor(hash);
        Entry<K, V>[] buckets = stripe.buckets;
        int bucketIndex = bucketIndex(hash, buckets.length);

        for (Entry<K, V> entry = buckets[bucketIndex]; entry != null; entry = entry.next) {
            if (entry.hash == hash && entry.key.equals(k)) {
                return true;
            }
        }
        return false;
    }

    public V get(K k) {
        requireNonNull(k);
        int hash = spreadHash(k.hashCode());
        Stripe<K, V> stripe = stripeFor(hash);
        Entry<K, V>[] buckets = stripe.buckets;
        int bucketIndex = bucketIndex(hash, buckets.length);

        for (Entry<K, V> entry = buckets[bucketIndex]; entry != null; entry = entry.next) {
            if (entry.hash == hash && entry.key.equals(k)) {
                return entry.value;
            }
        }
        return null;
    }

    public V put(K k, V v) {
        requireNonNull(k);
        requireNonNull(v);

        int hash = spreadHash(k.hashCode());
        Stripe<K, V> stripe = stripeFor(hash);

        synchronized (stripe) {
            Entry<K, V>[] buckets = stripe.buckets;
            int bucketIndex = bucketIndex(hash, buckets.length);
            Entry<K, V> head = buckets[bucketIndex];

            int prefixLength = 0;
            Entry<K, V> found = head;
            while (found != null && !(found.hash == hash && found.key.equals(k))) {
                prefixLength++;
                found = found.next;
            }

            if (found != null) {
                Entry<K, V> updated = new Entry<K, V>(
                        found.key, v, hash, found.next);
                for (Entry<K, V> entry = head; prefixLength > 0; prefixLength--) {
                    updated = new Entry<K, V>(
                            entry.key, entry.value, entry.hash, updated);
                    entry = entry.next;
                }

                Entry<K, V>[] replacement = copyBuckets(buckets);
                replacement[bucketIndex] = updated;
                stripe.buckets = replacement;
                return found.value;
            }

            if (stripe.count == Integer.MAX_VALUE) {
                throw new IllegalStateException("Map is too large");
            }

            int newCount = stripe.count + 1;
            Entry<K, V> updatedHead = new Entry<K, V>(k, v, hash, head);
            Entry<K, V>[] replacement = copyBuckets(buckets);
            replacement[bucketIndex] = updatedHead;

            if (newCount > threshold(buckets.length) && buckets.length < (1 << 30)) {
                replacement = rehash(replacement, buckets.length << 1);
            }

            stripe.buckets = replacement;
            stripe.count = newCount;
            return null;
        }
    }

    public V remove(K k) {
        requireNonNull(k);

        int hash = spreadHash(k.hashCode());
        Stripe<K, V> stripe = stripeFor(hash);

        synchronized (stripe) {
            Entry<K, V>[] buckets = stripe.buckets;
            int bucketIndex = bucketIndex(hash, buckets.length);
            Entry<K, V> head = buckets[bucketIndex];

            int prefixLength = 0;
            Entry<K, V> found = head;
            while (found != null && !(found.hash == hash && found.key.equals(k))) {
                prefixLength++;
                found = found.next;
            }

            if (found == null) {
                return null;
            }

            Entry<K, V> updated = found.next;
            Entry<K, V> entry = head;
            for (int i = 0; i < prefixLength; i++) {
                updated = new Entry<K, V>(
                        entry.key, entry.value, entry.hash, updated);
                entry = entry.next;
            }

            Entry<K, V>[] replacement = copyBuckets(buckets);
            replacement[bucketIndex] = updated;
            stripe.buckets = replacement;
            stripe.count--;
            return found.value;
        }
    }

    public int size() {
        long total = 0;
        for (Stripe<K, V> stripe : stripes) {
            total += stripe.count;
        }
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    public void forEach(java.util.function.BiConsumer<K, V> consumer) {
        if (consumer == null) {
            throw new NullPointerException();
        }

        for (Stripe<K, V> stripe : stripes) {
            Entry<K, V>[] buckets = stripe.buckets;
            for (Entry<K, V> bucket : buckets) {
                for (Entry<K, V> entry = bucket; entry != null; entry = entry.next) {
                    consumer.accept(entry.key, entry.value);
                }
            }
        }
    }

    private Stripe<K, V> stripeFor(int hash) {
        return stripes[hash & stripeMask];
    }

    private int bucketIndex(int hash, int bucketCount) {
        return (hash >>> stripeShift) & (bucketCount - 1);
    }

    private static int spreadHash(int hash) {
        return hash ^ (hash >>> 16);
    }

    private static int threshold(int bucketCount) {
        return bucketCount - (bucketCount >>> 2);
    }

    private static void requireNonNull(Object value) {
        if (value == null) {
            throw new NullPointerException();
        }
    }

    @SuppressWarnings("unchecked")
    private Entry<K, V>[] newEntryArray(int length) {
        return (Entry<K, V>[]) new Entry[length];
    }

    private Entry<K, V>[] copyBuckets(Entry<K, V>[] buckets) {
        Entry<K, V>[] copy = newEntryArray(buckets.length);
        for (int i = 0; i < buckets.length; i++) {
            copy[i] = buckets[i];
        }
        return copy;
    }

    private Entry<K, V>[] rehash(Entry<K, V>[] buckets, int newLength) {
        Entry<K, V>[] replacement = newEntryArray(newLength);
        for (Entry<K, V> bucket : buckets) {
            for (Entry<K, V> entry = bucket; entry != null; entry = entry.next) {
                int index = bucketIndex(entry.hash, newLength);
                replacement[index] = new Entry<K, V>(
                        entry.key, entry.value, entry.hash, replacement[index]);
            }
        }
        return replacement;
    }

    private static final class Stripe<K, V> {
        private volatile Entry<K, V>[] buckets;
        private volatile int count;

        private Stripe(Entry<K, V>[] buckets) {
            this.buckets = buckets;
        }
    }

    private static final class Entry<K, V> {
        private final K key;
        private final V value;
        private final int hash;
        private final Entry<K, V> next;

        private Entry(K key, V value, int hash, Entry<K, V> next) {
            this.key = key;
            this.value = value;
            this.hash = hash;
            this.next = next;
        }
    }
}

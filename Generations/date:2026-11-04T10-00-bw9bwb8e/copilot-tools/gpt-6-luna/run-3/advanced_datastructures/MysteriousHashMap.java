@MysteriousHashMap.ThreadSafe
public class MysteriousHashMap<K, V> {
    @interface ThreadSafe {}

    private static final int STRIPE_COUNT = 16;
    private static final int INITIAL_BUCKET_COUNT = 16;

    public interface BiConsumer<K, V> {
        void accept(K key, V value);
    }

    private static final class Entry<K, V> {
        final K key;
        final V value;
        final Entry<K, V> next;

        Entry(K key, V value, Entry<K, V> next) {
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }

    private static final class Stripe {
        volatile Object[] buckets;
        int size;

        Stripe(int bucketCount) {
            buckets = new Object[bucketCount];
        }
    }

    private final Stripe[] stripes;

    public MysteriousHashMap() {
        stripes = new Stripe[STRIPE_COUNT];
        for (int i = 0; i < stripes.length; i++) {
            stripes[i] = new Stripe(INITIAL_BUCKET_COUNT);
        }
    }

    public boolean containsKey(K k) {
        requireKey(k);
        int hash = spread(k.hashCode());
        Stripe stripe = stripes[hash & (stripes.length - 1)];
        Object[] buckets = stripe.buckets;
        Entry<K, V> entry = entryAt(buckets, hash & (buckets.length - 1));
        while (entry != null) {
            if (entry.key.equals(k)) {
                return true;
            }
            entry = entry.next;
        }
        return false;
    }

    public V get(K k) {
        requireKey(k);
        int hash = spread(k.hashCode());
        Stripe stripe = stripes[hash & (stripes.length - 1)];
        Object[] buckets = stripe.buckets;
        Entry<K, V> entry = entryAt(buckets, hash & (buckets.length - 1));
        while (entry != null) {
            if (entry.key.equals(k)) {
                return entry.value;
            }
            entry = entry.next;
        }
        return null;
    }

    public V put(K k, V v) {
        requireKey(k);
        if (v == null) {
            throw new NullPointerException();
        }

        int hash = spread(k.hashCode());
        Stripe stripe = stripes[hash & (stripes.length - 1)];
        synchronized (stripe) {
            Object[] buckets = stripe.buckets;
            int bucketIndex = hash & (buckets.length - 1);
            Entry<K, V> head = entryAt(buckets, bucketIndex);
            Entry<K, V> prefix = null;
            Entry<K, V> current = head;
            while (current != null && !current.key.equals(k)) {
                prefix = new Entry<K, V>(current.key, current.value, prefix);
                current = current.next;
            }

            if (current != null) {
                V previousValue = current.value;
                Entry<K, V> replacement =
                    new Entry<K, V>(current.key, v, current.next);
                while (prefix != null) {
                    replacement = new Entry<K, V>(
                        prefix.key, prefix.value, replacement);
                    prefix = prefix.next;
                }
                Object[] updatedBuckets = buckets.clone();
                updatedBuckets[bucketIndex] = replacement;
                stripe.buckets = updatedBuckets;
                return previousValue;
            }

            if (buckets.length < (1 << 30)
                    && stripe.size + 1 > buckets.length - (buckets.length >>> 2)) {
                Object[] resizedBuckets = new Object[buckets.length << 1];
                for (int i = 0; i < buckets.length; i++) {
                    Entry<K, V> entry = entryAt(buckets, i);
                    while (entry != null) {
                        int resizedIndex =
                            spread(entry.key.hashCode()) & (resizedBuckets.length - 1);
                        Entry<K, V> resizedHead = entryAt(resizedBuckets, resizedIndex);
                        resizedBuckets[resizedIndex] =
                            new Entry<K, V>(entry.key, entry.value, resizedHead);
                        entry = entry.next;
                    }
                }
                int resizedIndex = hash & (resizedBuckets.length - 1);
                resizedBuckets[resizedIndex] =
                    new Entry<K, V>(k, v, entryAt(resizedBuckets, resizedIndex));
                stripe.size++;
                stripe.buckets = resizedBuckets;
            } else {
                Object[] updatedBuckets = buckets.clone();
                updatedBuckets[bucketIndex] = new Entry<K, V>(k, v, head);
                stripe.size++;
                stripe.buckets = updatedBuckets;
            }
            return null;
        }
    }

    public V remove(K k) {
        requireKey(k);
        int hash = spread(k.hashCode());
        Stripe stripe = stripes[hash & (stripes.length - 1)];
        synchronized (stripe) {
            Object[] buckets = stripe.buckets;
            int bucketIndex = hash & (buckets.length - 1);
            Entry<K, V> prefix = null;
            Entry<K, V> current = entryAt(buckets, bucketIndex);
            while (current != null && !current.key.equals(k)) {
                prefix = new Entry<K, V>(current.key, current.value, prefix);
                current = current.next;
            }
            if (current == null) {
                return null;
            }

            Entry<K, V> replacement = current.next;
            while (prefix != null) {
                replacement = new Entry<K, V>(
                    prefix.key, prefix.value, replacement);
                prefix = prefix.next;
            }
            Object[] updatedBuckets = buckets.clone();
            updatedBuckets[bucketIndex] = replacement;
            stripe.size--;
            stripe.buckets = updatedBuckets;
            return current.value;
        }
    }

    public int size() {
        long total = totalSize(0);
        return total >= 0x7fffffffL ? 0x7fffffff : (int) total;
    }

    public void forEach(BiConsumer<K, V> consumer) {
        if (consumer == null) {
            throw new NullPointerException();
        }
        for (int i = 0; i < stripes.length; i++) {
            Object[] buckets = stripes[i].buckets;
            for (int j = 0; j < buckets.length; j++) {
                Entry<K, V> entry = entryAt(buckets, j);
                while (entry != null) {
                    consumer.accept(entry.key, entry.value);
                    entry = entry.next;
                }
            }
        }
    }

    private long totalSize(int stripeIndex) {
        if (stripeIndex == stripes.length) {
            return 0L;
        }
        synchronized (stripes[stripeIndex]) {
            return stripes[stripeIndex].size + totalSize(stripeIndex + 1);
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

    @SuppressWarnings("unchecked")
    private static <K, V> Entry<K, V> entryAt(Object[] buckets, int index) {
        return (Entry<K, V>) buckets[index];
    }
}

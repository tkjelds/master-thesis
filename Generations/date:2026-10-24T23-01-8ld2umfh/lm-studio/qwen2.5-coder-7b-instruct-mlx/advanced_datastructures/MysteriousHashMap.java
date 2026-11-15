import java.util.function.BiConsumer;

@MysteriousHashMap.ThreadSafe
public class MysteriousHashMap<K, V> {
    @interface ThreadSafe {}

    private static final int DEFAULT_STRIPE_COUNT = 16;
    private Stripe<K, V>[] stripes;

    @SuppressWarnings("unchecked")
    public MysteriousHashMap() {
        stripes = (Stripe<K, V>[]) new Stripe[DEFAULT_STRIPE_COUNT];
        for (int i = 0; i < stripes.length; i++) {
            stripes[i] = new Stripe<>();
        }
    }

    private int getStripeIndex(K key) {
        return Math.abs(key.hashCode()) % stripes.length;
    }

    public boolean containsKey(K k) {
        int stripeIndex = getStripeIndex(k);
        return stripes[stripeIndex].containsKey(k);
    }

    public V get(K k) {
        int stripeIndex = getStripeIndex(k);
        return stripes[stripeIndex].get(k);
    }

    public V put(K k, V v) {
        int stripeIndex = getStripeIndex(k);
        return stripes[stripeIndex].put(k, v);
    }

    public V remove(K k) {
        int stripeIndex = getStripeIndex(k);
        return stripes[stripeIndex].remove(k);
    }

    public int size() {
        int size = 0;
        for (Stripe<K, V> stripe : stripes) {
            size += stripe.size();
        }
        return size;
    }

    public void forEach(BiConsumer<K, V> consumer) {
        for (Stripe<K, V> stripe : stripes) {
            stripe.forEach(consumer);
        }
    }

    private static class Stripe<K, V> {
        private Bucket[] buckets;
        private int size;

        @SuppressWarnings("unchecked")
        public Stripe() {
            buckets = (Bucket[]) new Bucket[16];
            size = 0;
        }

        private int getBucketIndex(Object key) {
            return Math.abs(key.hashCode()) % buckets.length;
        }

        public boolean containsKey(K k) {
            int bucketIndex = getBucketIndex(k);
            Bucket bucket = buckets[bucketIndex];
            return bucket != null && bucket.containsKey(k);
        }

        public V get(K k) {
            int bucketIndex = getBucketIndex(k);
            Bucket bucket = buckets[bucketIndex];
            return bucket != null ? bucket.get(k) : null;
        }

        public V put(K k, V v) {
            synchronized (this) {
                int bucketIndex = getBucketIndex(k);
                Bucket oldBucket = buckets[bucketIndex];
                Bucket newBucket;
                if (oldBucket == null) {
                    newBucket = new Bucket();
                } else {
                    newBucket = oldBucket.copy();
                }
                V oldValue = newBucket.put(k, v);
                buckets[bucketIndex] = newBucket;
                if (oldValue == null) {
                    size++;
                }
                return oldValue;
            }
        }

        public V remove(K k) {
            synchronized (this) {
                int bucketIndex = getBucketIndex(k);
                Bucket oldBucket = buckets[bucketIndex];
                if (oldBucket == null) {
                    return null;
                }
                Bucket newBucket = oldBucket.copy();
                V oldValue = newBucket.remove(k);
                buckets[bucketIndex] = newBucket;
                if (oldValue != null) {
                    size--;
                }
                return oldValue;
            }
        }

        public int size() {
            synchronized (this) {
                return size;
            }
        }

        public void forEach(BiConsumer<K, V> consumer) {
            synchronized (this) {
                for (Bucket bucket : buckets) {
                    if (bucket != null) {
                        bucket.forEach(consumer);
                    }
                }
            }
        }

        private static class Bucket<K, V> {
            private final Node<K, V>[] entries;

            @SuppressWarnings("unchecked")
            public Bucket() {
                entries = (Node<K, V>[]) new Node[16];
            }

            public void forEach(BiConsumer<K, V> consumer) {
                for (Node<K, V> entry : entries) {
                    if (entry != null) {
                        consumer.accept(entry.key, entry.value);
                    }
                }
            }

            public boolean containsKey(K key) {
                return get(key) != null;
            }

            @SuppressWarnings("unchecked")
            public V get(K key) {
                int index = Math.abs(key.hashCode()) % entries.length;
                Node<K, V> entry = entries[index];
                return entry != null && key.equals(entry.key) ? entry.value : null;
            }

            @SuppressWarnings("unchecked")
            public V put(K key, V value) {
                int index = Math.abs(key.hashCode()) % entries.length;
                Node<K, V> oldEntry = entries[index];
                if (oldEntry != null && key.equals(oldEntry.key)) {
                    return oldEntry.value;
                } else {
                    Node<K, V> newEntry = new Node<>(key, value);
                    entries[index] = newEntry;
                    return null;
                }
            }

            @SuppressWarnings("unchecked")
            public V remove(K key) {
                int index = Math.abs(key.hashCode()) % entries.length;
                Node<K, V> oldEntry = entries[index];
                if (oldEntry != null && key.equals(oldEntry.key)) {
                    entries[index] = null;
                    return oldEntry.value;
                } else {
                    return null;
                }
            }

            @SuppressWarnings("unchecked")
            public Bucket<K, V> copy() {
                int newSize = entries.length;
                Node<K, V>[] newEntries = (Node<K, V>[]) new Node[newSize];
                for (int i = 0; i < entries.length; i++) {
                    Node<K, V> entry = entries[i];
                    if (entry != null) {
                        newEntries[i] = new Node<>(entry.key, entry.value);
                    }
                }
                return new Bucket(newEntries);
            }

            @SuppressWarnings("unchecked")
            private Bucket(Node<K, V>[] newEntries) {
                entries = newEntries;
            }

            private static class Node<K, V> {
                final K key;
                final V value;

                public Node(K key, V value) {
                    this.key = key;
                    this.value = value;
                }
            }
        }
    }
}

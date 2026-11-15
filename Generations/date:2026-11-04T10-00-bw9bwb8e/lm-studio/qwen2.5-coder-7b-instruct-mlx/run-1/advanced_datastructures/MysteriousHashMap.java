@MysteriousHashMap.ThreadSafe
class MysteriousHashMap<K, V> {
    @interface ThreadSafe {}

    private static final int INITIAL_BUCKET_SIZE = 16;
    private static final float LOAD_FACTOR = 0.75f;

    private Stripe<K, V>[] stripes;
    private int size;

    @SuppressWarnings("unchecked")
    public MysteriousHashMap() {
        stripes = new Stripe[Runtime.getRuntime().availableProcessors()];
        for (int i = 0; i < stripes.length; i++) {
            stripes[i] = new Stripe<>(INITIAL_BUCKET_SIZE);
        }
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
        synchronized (this) {
            return size;
        }
    }

    public void forEach(BiConsumer<K, V> consumer) {
        for (Stripe<K, V> stripe : stripes) {
            stripe.forEach(consumer);
        }
    }

    private int getStripeIndex(K k) {
        return Math.abs(k.hashCode()) % stripes.length;
    }

    @SuppressWarnings("unchecked")
    private void rehashStripe(int stripeIndex) {
        synchronized (stripes[stripeIndex]) {
            int newSize = stripes[stripeIndex].buckets.length * 2;
            Bucket<K, V>[] newBuckets = new Bucket[newSize];
            for (Bucket<K, V> bucket : stripes[stripeIndex].buckets) {
                if (bucket != null) {
                    for (Map.Entry<K, V> entry : bucket.entries) {
                        int newIndex = Math.abs(entry.getKey().hashCode()) % newSize;
                        if (newBuckets[newIndex] == null) {
                            newBuckets[newIndex] = new Bucket<>();
                        }
                        newBuckets[newIndex].entries.add(entry);
                    }
                }
            }
            stripes[stripeIndex].buckets = newBuckets;
        }
    }

    private static class Stripe<K, V> {
        Bucket<K, V>[] buckets;
        int size;

        @SuppressWarnings("unchecked")
        Stripe(int initialSize) {
            this.buckets = new Bucket[initialSize];
            for (int i = 0; i < buckets.length; i++) {
                buckets[i] = new Bucket<>();
            }
        }

        boolean containsKey(K k) {
            int index = Math.abs(k.hashCode()) % buckets.length;
            return findEntry(index, k) != null;
        }

        V get(K k) {
            int index = Math.abs(k.hashCode()) % buckets.length;
            Map.Entry<K, V> entry = findEntry(index, k);
            return entry != null ? entry.getValue() : null;
        }

        V put(K k, V v) {
            int index = Math.abs(k.hashCode()) % buckets.length;
            Map.Entry<K, V> oldEntry = findEntry(index, k);
            if (oldEntry != null) {
                V oldValue = oldEntry.getValue();
                oldEntry.setValue(v);
                return oldValue;
            }
            synchronized (buckets[index]) {
                if (size() >= buckets.length * 0.75f) {
                    rehashStripe(index);
                }
                Bucket<K, V> bucket = buckets[index];
                Map.Entry<K, V> newEntry = new AbstractMap.SimpleImmutableEntry<>(k, v);
                bucket.entries.add(newEntry);
                size++;
                return null;
            }
        }

        V remove(K k) {
            int index = Math.abs(k.hashCode()) % buckets.length;
            Map.Entry<K, V> entry = findEntry(index, k);
            if (entry == null) {
                return null;
            }
            synchronized (buckets[index]) {
                bucket.entries.remove(entry);
                size--;
                return entry.getValue();
            }
        }

        int size() {
            synchronized (this) {
                return size;
            }
        }

        void forEach(BiConsumer<K, V> consumer) {
            for (Bucket<K, V> bucket : buckets) {
                synchronized (bucket) {
                    for (Map.Entry<K, V> entry : bucket.entries) {
                        consumer.accept(entry.getKey(), entry.getValue());
                    }
                }
            }
        }

        @SuppressWarnings("unchecked")
        private Map.Entry<K, V> findEntry(int index, K k) {
            for (Map.Entry<K, V> entry : buckets[index].entries) {
                if (entry.getKey().equals(k)) {
                    return entry;
                }
            }
            return null;
        }

        private static class Bucket<K, V> {
            List<Map.Entry<K, V>> entries = new ArrayList<>();

            int size() {
                return entries.size();
            }
        }

        @SuppressWarnings("unchecked")
        void rehash(int index) {
            int newSize = buckets.length * 2;
            Bucket<K, V>[] newBuckets = new Bucket[newSize];
            for (Bucket<K, V> bucket : buckets) {
                if (bucket != null) {
                    for (Map.Entry<K, V> entry : bucket.entries) {
                        int newIndex = Math.abs(entry.getKey().hashCode()) % newSize;
                        if (newBuckets[newIndex] == null) {
                            newBuckets[newIndex] = new Bucket<>();
                        }
                        newBuckets[newIndex].entries.add(entry);
                    }
                }
            }
            buckets = newBuckets;
        }
    }
}

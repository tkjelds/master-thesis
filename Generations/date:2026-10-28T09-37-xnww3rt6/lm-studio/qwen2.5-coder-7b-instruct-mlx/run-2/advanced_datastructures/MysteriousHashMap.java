import java.util.function.BiConsumer;
import java.util.Arrays;

@MysteriousHashMap.ThreadSafe
public class MysteriousHashMap<K, V> {
    @interface ThreadSafe {}

    private static final int DEFAULT_SIZE = 16;
    private static final float LOAD_FACTOR = 0.75f;

    private Stripe<K, V>[] stripes;
    private int size;

    @SuppressWarnings("unchecked")
    public MysteriousHashMap() {
        this.stripes = new Stripe[DEFAULT_SIZE];
        for (int i = 0; i < DEFAULT_SIZE; i++) {
            stripes[i] = new Stripe<>();
        }
    }

    public boolean containsKey(K k) {
        int hash = k.hashCode();
        return stripes[hash & (stripes.length - 1)].containsKey(k);
    }

    public V get(K k) {
        int hash = k.hashCode();
        return stripes[hash & (stripes.length - 1)].get(k);
    }

    public V put(K k, V v) {
        int hash = k.hashCode();
        synchronized (stripes[hash & (stripes.length - 1)]) {
            return stripes[hash & (stripes.length - 1)].put(k, v);
        }
    }

    public V remove(K k) {
        int hash = k.hashCode();
        synchronized (stripes[hash & (stripes.length - 1)]) {
            return stripes[hash & (stripes.length - 1)].remove(k);
        }
    }

    public int size() {
        return size;
    }

    public void forEach(BiConsumer<K, V> consumer) {
        for (Stripe<K, V> stripe : stripes) {
            synchronized (stripe) {
                stripe.forEach(consumer);
            }
        }
    }

    private static class Stripe<K, V> {
        private Node<K, V>[] buckets;
        private int count;

        @SuppressWarnings("unchecked")
        public Stripe() {
            this.buckets = new Node[DEFAULT_SIZE];
            for (int i = 0; i < DEFAULT_SIZE; i++) {
                buckets[i] = new Node<>();
            }
        }

        public boolean containsKey(K k) {
            int hash = k.hashCode();
            return buckets[hash & (buckets.length - 1)].containsKey(k);
        }

        public V get(K k) {
            int hash = k.hashCode();
            return buckets[hash & (buckets.length - 1)].get(k);
        }

        public V put(K k, V v) {
            int hash = k.hashCode();
            Node<K, V> node = buckets[hash & (buckets.length - 1)];
            V oldValue = node.put(k, v);
            if (oldValue == null) {
                count++;
                resizeIfNeeded();
            }
            return oldValue;
        }

        public V remove(K k) {
            int hash = k.hashCode();
            Node<K, V> node = buckets[hash & (buckets.length - 1)];
            V oldValue = node.remove(k);
            if (oldValue != null) {
                count--;
            }
            return oldValue;
        }

        private void resizeIfNeeded() {
            if (count / buckets.length > LOAD_FACTOR) {
                @SuppressWarnings("unchecked")
                Node<K, V>[] newBuckets = new Node[buckets.length * 2];
                for (int i = 0; i < buckets.length; i++) {
                    Node<K, V> bucket = buckets[i];
                    for (KVEntry<K, V> entry : bucket.entries) {
                        int newHash = entry.getKey().hashCode();
                        newBuckets[newHash & (newBuckets.length - 1)].put(entry.getKey(), entry.getValue());
                    }
                }
                buckets = newBuckets;
            }
        }

        public void forEach(BiConsumer<K, V> consumer) {
            for (Node<K, V> bucket : buckets) {
                for (KVEntry<K, V> entry : bucket.entries) {
                    consumer.accept(entry.getKey(), entry.getValue());
                }
            }
        }

        private static class Node<K, V> {
            private KVEntry<K, V>[] entries;

            @SuppressWarnings("unchecked")
            public Node() {
                this.entries = new KVEntry[4];
            }

            public boolean containsKey(K k) {
                for (KVEntry<K, V> entry : entries) {
                    if (entry != null && entry.getKey().equals(k)) {
                        return true;
                    }
                }
                return false;
            }

            public V get(K k) {
                for (KVEntry<K, V> entry : entries) {
                    if (entry != null && entry.getKey().equals(k)) {
                        return entry.getValue();
                    }
                }
                return null;
            }

            public V put(K k, V v) {
                for (int i = 0; i < entries.length; i++) {
                    KVEntry<K, V> entry = entries[i];
                    if (entry == null) {
                        entries[i] = new KVEntry<>(k, v);
                        return null;
                    } else if (entry.getKey().equals(k)) {
                        V oldValue = entry.getValue();
                        entries[i] = new KVEntry<>(k, v);
                        return oldValue;
                    }
                }
                return entries[entries.length - 1].getValue(); // Should not reach here
            }

            public V remove(K k) {
                for (int i = 0; i < entries.length; i++) {
                    KVEntry<K, V> entry = entries[i];
                    if (entry != null && entry.getKey().equals(k)) {
                        V oldValue = entry.getValue();
                        entries[i] = null;
                        return oldValue;
                    }
                }
                return null;
            }

            private static class KVEntry<K, V> {
                private K key;
                private V value;

                public KVEntry(K key, V value) {
                    this.key = key;
                    this.value = value;
                }

                public K getKey() {
                    return key;
                }

                public V getValue() {
                    return value;
                }
            }
        }
    }
}

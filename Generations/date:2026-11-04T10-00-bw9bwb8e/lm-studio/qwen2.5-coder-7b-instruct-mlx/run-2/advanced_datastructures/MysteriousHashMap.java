import java.util.function.BiConsumer;

@MysteriousHashMap.ThreadSafe
public class MysteriousHashMap<K, V> {
    @interface ThreadSafe {}

    private static final int INITIAL_SIZE = 16;
    private final Stripe<K, V>[] stripes;

    @SuppressWarnings("unchecked")
    public MysteriousHashMap() {
        this.stripes = (Stripe<K, V>[]) new Stripe<?, ?>[INITIAL_SIZE];
        for (int i = 0; i < INITIAL_SIZE; i++) {
            stripes[i] = new Stripe<>();
        }
    }

    private int hash(Object key) {
        return Math.abs(key.hashCode()) % stripes.length;
    }

    public boolean containsKey(K k) {
        int index = hash(k);
        return stripes[index].containsKey(k);
    }

    public V get(K k) {
        int index = hash(k);
        return stripes[index].get(k);
    }

    public V put(K k, V v) {
        int index = hash(k);
        synchronized (stripes[index]) {
            return stripes[index].put(k, v);
        }
    }

    public V remove(K k) {
        int index = hash(k);
        synchronized (stripes[index]) {
            return stripes[index].remove(k);
        }
    }

    public int size() {
        int count = 0;
        for (Stripe<K, V> stripe : stripes) {
            synchronized (stripe) {
                count += stripe.size();
            }
        }
        return count;
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
        private int size;

        @SuppressWarnings("unchecked")
        public Stripe() {
            this.buckets = (Node<K, V>[]) new Node<?, ?>[INITIAL_SIZE];
            for (int i = 0; i < INITIAL_SIZE; i++) {
                buckets[i] = null;
            }
        }

        public boolean containsKey(K k) {
            int index = hash(k);
            Node<K, V> node = buckets[index];
            while (node != null) {
                if (node.key.equals(k)) {
                    return true;
                }
                node = node.next;
            }
            return false;
        }

        public V get(K k) {
            int index = hash(k);
            Node<K, V> node = buckets[index];
            while (node != null) {
                if (node.key.equals(k)) {
                    return node.value;
                }
                node = node.next;
            }
            return null;
        }

        public V put(K k, V v) {
            int index = hash(k);
            synchronized (this) {
                if (buckets[index] == null) {
                    buckets[index] = new Node<>(k, v);
                } else {
                    Node<K, V> node = buckets[index];
                    while (node != null) {
                        if (node.key.equals(k)) {
                            V oldValue = node.value;
                            node.value = v;
                            return oldValue;
                        }
                        node = node.next;
                    }
                    buckets[index] = new Node<>(k, v);
                }
            }
            return null;
        }

        public V remove(K k) {
            int index = hash(k);
            synchronized (this) {
                Node<K, V> prev = null;
                Node<K, V> node = buckets[index];
                while (node != null) {
                    if (node.key.equals(k)) {
                        V oldValue = node.value;
                        if (prev == null) {
                            buckets[index] = node.next;
                        } else {
                            prev.next = node.next;
                        }
                        return oldValue;
                    }
                    prev = node;
                    node = node.next;
                }
            }
            return null;
        }

        public int size() {
            synchronized (this) {
                int count = 0;
                for (Node<K, V> node : buckets) {
                    while (node != null) {
                        count++;
                        node = node.next;
                    }
                }
                return count;
            }
        }

        public void forEach(BiConsumer<K, V> consumer) {
            synchronized (this) {
                for (Node<K, V> node : buckets) {
                    while (node != null) {
                        consumer.accept(node.key, node.value);
                        node = node.next;
                    }
                }
            }
        }

        private static class Node<K, V> {
            final K key;
            final V value;
            Node<K, V> next;

            public Node(K key, V value) {
                this.key = key;
                this.value = value;
            }
        }

        private int hash(Object key) {
            return Math.abs(key.hashCode()) % buckets.length;
        }
    }
}

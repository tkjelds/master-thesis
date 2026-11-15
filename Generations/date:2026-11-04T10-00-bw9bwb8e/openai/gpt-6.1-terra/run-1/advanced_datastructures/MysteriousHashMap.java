@MysteriousHashMap.ThreadSafe
public class MysteriousHashMap<K, V> {
    @interface ThreadSafe {}

    public interface BiConsumer<K, V> {
        void accept(K key, V value);
    }

    private static final int STRIPE_COUNT = 16;
    private static final int STRIPE_BITS = 4;
    private static final int INITIAL_BUCKETS = 8;
    private static final int MAX_BUCKETS = 1 << 30;

    private static final class Node<K, V> {
        final int hash;
        final K key;
        final V value;
        final Node<K, V> next;

        Node(int hash, K key, V value, Node<K, V> next) {
            this.hash = hash;
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }

    private static final class Stripe<K, V> {
        volatile Node<K, V>[] buckets;
        long size;

        Stripe() {
            buckets = newBuckets(INITIAL_BUCKETS);
        }
    }

    private final Stripe<K, V>[] stripes;

    @SuppressWarnings("unchecked")
    public MysteriousHashMap() {
        Stripe<K, V>[] created = (Stripe<K, V>[]) new Stripe[STRIPE_COUNT];
        for (int i = 0; i < created.length; i++) {
            created[i] = new Stripe<K, V>();
        }
        stripes = created;
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Node<K, V>[] newBuckets(int length) {
        return (Node<K, V>[]) new Node[length];
    }

    private static int hash(Object key) {
        int h = key.hashCode();
        return h ^ (h >>> 16);
    }

    private static int bucketIndex(int hash, int length) {
        return (hash >>> STRIPE_BITS) & (length - 1);
    }

    private Stripe<K, V> stripeFor(int hash) {
        return stripes[hash & (STRIPE_COUNT - 1)];
    }

    public boolean containsKey(K key) {
        if (key == null) {
            throw new NullPointerException();
        }

        int h = hash(key);
        Node<K, V>[] buckets = stripeFor(h).buckets;
        Node<K, V> node = buckets[bucketIndex(h, buckets.length)];

        while (node != null) {
            if (node.hash == h && (node.key == key || key.equals(node.key))) {
                return true;
            }
            node = node.next;
        }
        return false;
    }

    public V get(K key) {
        if (key == null) {
            throw new NullPointerException();
        }

        int h = hash(key);
        Node<K, V>[] buckets = stripeFor(h).buckets;
        Node<K, V> node = buckets[bucketIndex(h, buckets.length)];

        while (node != null) {
            if (node.hash == h && (node.key == key || key.equals(node.key))) {
                return node.value;
            }
            node = node.next;
        }
        return null;
    }

    public V put(K key, V value) {
        if (key == null || value == null) {
            throw new NullPointerException();
        }

        int h = hash(key);
        Stripe<K, V> stripe = stripeFor(h);

        synchronized (stripe) {
            Node<K, V>[] oldBuckets = stripe.buckets;
            int index = bucketIndex(h, oldBuckets.length);
            Node<K, V> found = null;

            for (Node<K, V> node = oldBuckets[index]; node != null; node = node.next) {
                if (node.hash == h && (node.key == key || key.equals(node.key))) {
                    found = node;
                    break;
                }
            }

            // A key's equals method can reenter this map and modify the stripe.
            if (stripe.buckets != oldBuckets) {
                throw new IllegalStateException("Recursive stripe modification");
            }

            if (found == null
                    && oldBuckets.length < MAX_BUCKETS
                    && stripe.size + 1 > ((long) oldBuckets.length * 3) / 4) {
                Node<K, V>[] newTable = newBuckets(oldBuckets.length * 2);
                for (int i = 0; i < oldBuckets.length; i++) {
                    for (Node<K, V> node = oldBuckets[i]; node != null; node = node.next) {
                        int newIndex = bucketIndex(node.hash, newTable.length);
                        newTable[newIndex] = new Node<K, V>(
                                node.hash, node.key, node.value, newTable[newIndex]);
                    }
                }
                int newIndex = bucketIndex(h, newTable.length);
                newTable[newIndex] = new Node<K, V>(h, key, value, newTable[newIndex]);
                stripe.size++;
                stripe.buckets = newTable;
                return null;
            }

            Node<K, V>[] newTable = newBuckets(oldBuckets.length);
            for (int i = 0; i < oldBuckets.length; i++) {
                newTable[i] = oldBuckets[i];
            }

            Node<K, V> newList = null;
            for (Node<K, V> node = oldBuckets[index]; node != null; node = node.next) {
                if (node != found) {
                    newList = new Node<K, V>(node.hash, node.key, node.value, newList);
                }
            }

            K storedKey = found == null ? key : found.key;
            newTable[index] = new Node<K, V>(h, storedKey, value, newList);
            if (found == null) {
                stripe.size++;
            }
            stripe.buckets = newTable;
            return found == null ? null : found.value;
        }
    }

    public V remove(K key) {
        if (key == null) {
            throw new NullPointerException();
        }

        int h = hash(key);
        Stripe<K, V> stripe = stripeFor(h);

        synchronized (stripe) {
            Node<K, V>[] oldBuckets = stripe.buckets;
            int index = bucketIndex(h, oldBuckets.length);
            Node<K, V> found = null;

            for (Node<K, V> node = oldBuckets[index]; node != null; node = node.next) {
                if (node.hash == h && (node.key == key || key.equals(node.key))) {
                    found = node;
                    break;
                }
            }

            if (stripe.buckets != oldBuckets) {
                throw new IllegalStateException("Recursive stripe modification");
            }
            if (found == null) {
                return null;
            }

            Node<K, V>[] newTable = newBuckets(oldBuckets.length);
            for (int i = 0; i < oldBuckets.length; i++) {
                newTable[i] = oldBuckets[i];
            }

            Node<K, V> newList = null;
            for (Node<K, V> node = oldBuckets[index]; node != null; node = node.next) {
                if (node != found) {
                    newList = new Node<K, V>(node.hash, node.key, node.value, newList);
                }
            }
            newTable[index] = newList;
            stripe.size--;
            stripe.buckets = newTable;
            return found.value;
        }
    }

    public int size() {
        long total = 0;
        for (int i = 0; i < stripes.length; i++) {
            Stripe<K, V> stripe = stripes[i];
            synchronized (stripe) {
                total += stripe.size;
            }
            if (total >= Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
        }
        return (int) total;
    }

    public void forEach(BiConsumer<K, V> consumer) {
        if (consumer == null) {
            throw new NullPointerException();
        }

        for (int i = 0; i < stripes.length; i++) {
            Node<K, V>[] buckets = stripes[i].buckets;
            for (int j = 0; j < buckets.length; j++) {
                for (Node<K, V> node = buckets[j]; node != null; node = node.next) {
                    consumer.accept(node.key, node.value);
                }
            }
        }
    }
}

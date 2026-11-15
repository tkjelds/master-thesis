@MysteriousHashMap.ThreadSafe
public class MysteriousHashMap<K, V> {
    @interface ThreadSafe {}

    @FunctionalInterface
    public interface BiConsumer<K, V> {
        void accept(K key, V value);
    }

    private static final int STRIPE_BITS = 4;
    private static final int STRIPE_COUNT = 1 << STRIPE_BITS;
    private static final int INITIAL_BUCKETS = 4;
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
        // Published arrays and their bucket lists are never modified.
        volatile Node<K, V>[] buckets;
        long size;

        Stripe() {
            buckets = newBucketArray(INITIAL_BUCKETS);
        }
    }

    private final Stripe<K, V>[] stripes;

    @SuppressWarnings("unchecked")
    public MysteriousHashMap() {
        stripes = (Stripe<K, V>[]) new Stripe<?, ?>[STRIPE_COUNT];
        for (int i = 0; i < stripes.length; i++) {
            stripes[i] = new Stripe<K, V>();
        }
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Node<K, V>[] newBucketArray(int length) {
        return (Node<K, V>[]) new Node<?, ?>[length];
    }

    private static int spread(int hash) {
        return hash ^ (hash >>> 16);
    }

    private Stripe<K, V> stripeFor(int hash) {
        return stripes[hash & (STRIPE_COUNT - 1)];
    }

    private static int bucketIndex(int hash, int length) {
        return (hash >>> STRIPE_BITS) & (length - 1);
    }

    public boolean containsKey(K k) {
        return get(k) != null;
    }

    public V get(K k) {
        if (k == null) {
            throw new NullPointerException();
        }
        int hash = spread(k.hashCode());
        Node<K, V>[] buckets = stripeFor(hash).buckets;
        Node<K, V> node = buckets[bucketIndex(hash, buckets.length)];
        while (node != null) {
            if (node.hash == hash && k.equals(node.key)) {
                return node.value;
            }
            node = node.next;
        }
        return null;
    }

    public V put(K k, V v) {
        if (k == null || v == null) {
            throw new NullPointerException();
        }
        int hash = spread(k.hashCode());
        Stripe<K, V> stripe = stripeFor(hash);

        synchronized (stripe) {
            Node<K, V>[] oldBuckets = stripe.buckets;
            int index = bucketIndex(hash, oldBuckets.length);
            Node<K, V> found = oldBuckets[index];

            while (found != null) {
                if (found.hash == hash && k.equals(found.key)) {
                    break;
                }
                found = found.next;
            }

            if (found != null) {
                Node<K, V>[] newBuckets = copyBuckets(oldBuckets);
                Node<K, V> rebuilt = null;
                for (Node<K, V> node = oldBuckets[index];
                     node != null; node = node.next) {
                    V value = node == found ? v : node.value;
                    rebuilt = new Node<K, V>(
                            node.hash, node.key, value, rebuilt);
                }
                newBuckets[index] = rebuilt;
                stripe.buckets = newBuckets;
                return found.value;
            }

            long newSize = stripe.size + 1;
            Node<K, V>[] newBuckets;
            if (oldBuckets.length < MAX_BUCKETS
                    && newSize > oldBuckets.length - (oldBuckets.length >>> 2)) {
                newBuckets = newBucketArray(oldBuckets.length << 1);
                for (int i = 0; i < oldBuckets.length; i++) {
                    for (Node<K, V> node = oldBuckets[i];
                         node != null; node = node.next) {
                        int newIndex = bucketIndex(node.hash, newBuckets.length);
                        newBuckets[newIndex] = new Node<K, V>(
                                node.hash, node.key, node.value, newBuckets[newIndex]);
                    }
                }
                index = bucketIndex(hash, newBuckets.length);
            } else {
                newBuckets = copyBuckets(oldBuckets);
            }

            newBuckets[index] = new Node<K, V>(hash, k, v, newBuckets[index]);
            stripe.size = newSize;
            stripe.buckets = newBuckets;
            return null;
        }
    }

    public V remove(K k) {
        if (k == null) {
            throw new NullPointerException();
        }
        int hash = spread(k.hashCode());
        Stripe<K, V> stripe = stripeFor(hash);

        synchronized (stripe) {
            Node<K, V>[] oldBuckets = stripe.buckets;
            int index = bucketIndex(hash, oldBuckets.length);
            Node<K, V> found = oldBuckets[index];

            while (found != null) {
                if (found.hash == hash && k.equals(found.key)) {
                    break;
                }
                found = found.next;
            }
            if (found == null) {
                return null;
            }

            Node<K, V>[] newBuckets = copyBuckets(oldBuckets);
            Node<K, V> rebuilt = null;
            for (Node<K, V> node = oldBuckets[index];
                 node != null; node = node.next) {
                if (node != found) {
                    rebuilt = new Node<K, V>(
                            node.hash, node.key, node.value, rebuilt);
                }
            }
            newBuckets[index] = rebuilt;
            stripe.size--;
            stripe.buckets = newBuckets;
            return found.value;
        }
    }

    private static <K, V> Node<K, V>[] copyBuckets(Node<K, V>[] source) {
        Node<K, V>[] copy = newBucketArray(source.length);
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }

    public int size() {
        return sizeWithLocks(0);
    }

    private int sizeWithLocks(int index) {
        if (index == stripes.length) {
            long total = 0;
            for (int i = 0; i < stripes.length; i++) {
                total += stripes[i].size;
                if (total >= Integer.MAX_VALUE) {
                    return Integer.MAX_VALUE;
                }
            }
            return (int) total;
        }
        synchronized (stripes[index]) {
            return sizeWithLocks(index + 1);
        }
    }

    public void forEach(BiConsumer<K, V> consumer) {
        if (consumer == null) {
            throw new NullPointerException();
        }
        for (int i = 0; i < stripes.length; i++) {
            Node<K, V>[] buckets = stripes[i].buckets;
            for (int j = 0; j < buckets.length; j++) {
                for (Node<K, V> node = buckets[j];
                     node != null; node = node.next) {
                    consumer.accept(node.key, node.value);
                }
            }
        }
    }
}

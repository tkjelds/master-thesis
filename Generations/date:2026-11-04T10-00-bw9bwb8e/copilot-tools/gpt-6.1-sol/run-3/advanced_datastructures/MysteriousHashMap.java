@MysteriousHashMap.ThreadSafe
public final class MysteriousHashMap<K, V> {
    @interface ThreadSafe {}

    private static final int STRIPE_BITS = 4;
    private static final int STRIPE_COUNT = 1 << STRIPE_BITS;
    private static final int INITIAL_BUCKET_COUNT = 8;
    private static final int MAX_BUCKET_COUNT = 1 << 30;

    public interface BiConsumer<K, V> {
        void accept(K key, V value);
    }

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

    private static final class Table<K, V> {
        // Neither the array nor its lists are modified after publication.
        final Node<K, V>[] buckets;
        final long size;

        Table(Node<K, V>[] buckets, long size) {
            this.buckets = buckets;
            this.size = size;
        }
    }

    private static final class Stripe<K, V> {
        volatile Table<K, V> table;

        Stripe() {
            table = new Table<K, V>(newBuckets(INITIAL_BUCKET_COUNT), 0);
        }
    }

    private final Stripe<K, V>[] stripes;

    public MysteriousHashMap() {
        stripes = (Stripe<K, V>[]) new Stripe<?, ?>[STRIPE_COUNT];
        for (int i = 0; i < stripes.length; i++) {
            stripes[i] = new Stripe<K, V>();
        }
    }

    private static <K, V> Node<K, V>[] newBuckets(int length) {
        return (Node<K, V>[]) new Node<?, ?>[length];
    }

    private static <K> int hash(K key) {
        // Dereferencing a null key supplies the required NullPointerException.
        int h = key.hashCode();
        return h ^ (h >>> 16);
    }

    private static int bucketIndex(int hash, int length) {
        // Stripe selection already consumes the low STRIPE_BITS bits.
        return (hash >>> STRIPE_BITS) & (length - 1);
    }

    private static <K, V> Node<K, V> find(
            Node<K, V> head, int hash, K key) {
        for (Node<K, V> n = head; n != null; n = n.next) {
            if (n.hash == hash && (n.key == key || key.equals(n.key))) {
                return n;
            }
        }
        return null;
    }

    public boolean containsKey(K key) {
        return get(key) != null;
    }

    public V get(K key) {
        int h = hash(key);
        Table<K, V> table = stripes[h & (STRIPE_COUNT - 1)].table;
        Node<K, V> node = find(
                table.buckets[bucketIndex(h, table.buckets.length)], h, key);
        return node == null ? null : node.value;
    }

    public V put(K key, V value) {
        if (value == null) {
            // Java specifies that throwing null raises NullPointerException.
            throw null;
        }
        int h = hash(key);
        Stripe<K, V> stripe = stripes[h & (STRIPE_COUNT - 1)];
        synchronized (stripe) {
            Table<K, V> old = stripe.table;
            int index = bucketIndex(h, old.buckets.length);
            Node<K, V> existing = find(old.buckets[index], h, key);
            Node<K, V>[] buckets;
            long newSize = old.size;

            if (existing != null) {
                buckets = copyBuckets(old.buckets);
                Node<K, V> replacement = new Node<K, V>(
                        h, existing.key, value, existing.next);
                buckets[index] = rebuild(
                        old.buckets[index], existing, replacement);
            } else {
                newSize++;
                int length = old.buckets.length;
                if (length < MAX_BUCKET_COUNT
                        && newSize > length - (length >>> 2)) {
                    buckets = grow(old.buckets);
                    index = bucketIndex(h, buckets.length);
                } else {
                    buckets = copyBuckets(old.buckets);
                }
                buckets[index] = new Node<K, V>(
                        h, key, value, buckets[index]);
            }

            // Publish the entire immutable stripe snapshot atomically.
            stripe.table = new Table<K, V>(buckets, newSize);
            return existing == null ? null : existing.value;
        }
    }

    public V remove(K key) {
        int h = hash(key);
        Stripe<K, V> stripe = stripes[h & (STRIPE_COUNT - 1)];
        synchronized (stripe) {
            Table<K, V> old = stripe.table;
            int index = bucketIndex(h, old.buckets.length);
            Node<K, V> existing = find(old.buckets[index], h, key);
            if (existing == null) {
                return null;
            }

            Node<K, V>[] buckets = copyBuckets(old.buckets);
            buckets[index] = rebuild(
                    old.buckets[index], existing, existing.next);
            stripe.table = new Table<K, V>(buckets, old.size - 1);
            return existing.value;
        }
    }

    private static <K, V> Node<K, V>[] copyBuckets(Node<K, V>[] source) {
        Node<K, V>[] copy = newBuckets(source.length);
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }

    private static <K, V> Node<K, V>[] grow(Node<K, V>[] source) {
        Node<K, V>[] result = newBuckets(source.length << 1);
        for (int i = 0; i < source.length; i++) {
            for (Node<K, V> n = source[i]; n != null; n = n.next) {
                int index = bucketIndex(n.hash, result.length);
                result[index] = new Node<K, V>(
                        n.hash, n.key, n.value, result[index]);
            }
        }
        return result;
    }

    private static <K, V> Node<K, V> rebuild(
            Node<K, V> head, Node<K, V> target, Node<K, V> replacement) {
        // Rebuild only the prefix, iteratively to avoid collision-induced
        // stack overflow. The immutable suffix remains shared.
        Node<K, V> reversed = null;
        for (Node<K, V> n = head; n != target; n = n.next) {
            reversed = new Node<K, V>(n.hash, n.key, n.value, reversed);
        }

        Node<K, V> result = replacement;
        for (Node<K, V> n = reversed; n != null; n = n.next) {
            result = new Node<K, V>(n.hash, n.key, n.value, result);
        }
        return result;
    }

    public int size() {
        return lockedSize(0);
    }

    private int lockedSize(int index) {
        if (index < stripes.length) {
            // Acquire all stripe locks in a fixed order for an exact size.
            synchronized (stripes[index]) {
                return lockedSize(index + 1);
            }
        }

        long total = 0;
        for (int i = 0; i < stripes.length; i++) {
            total += stripes[i].table.size;
            if (total >= 2147483647L) {
                return 2147483647;
            }
        }
        return (int) total;
    }

    public void forEach(BiConsumer<K, V> consumer) {
        if (consumer == null) {
            throw null;
        }

        // Weakly consistent across stripes; callbacks run without map locks.
        for (int i = 0; i < stripes.length; i++) {
            Table<K, V> table = stripes[i].table;
            for (int j = 0; j < table.buckets.length; j++) {
                for (Node<K, V> n = table.buckets[j]; n != null; n = n.next) {
                    consumer.accept(n.key, n.value);
                }
            }
        }
    }
}

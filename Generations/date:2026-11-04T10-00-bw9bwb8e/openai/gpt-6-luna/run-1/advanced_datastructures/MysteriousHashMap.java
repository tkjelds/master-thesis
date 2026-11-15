@MysteriousHashMap.ThreadSafe
public class MysteriousHashMap<K, V> {
    @interface ThreadSafe {}

    @FunctionalInterface
    public interface BiConsumer<K, V> {
        void accept(K key, V value);
    }

    private static final class Node {
        final int hash;
        final Object key;
        final Object value;
        final Node next;

        Node(int hash, Object key, Object value, Node next) {
            this.hash = hash;
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }

    private static final class Stripe {
        volatile Node[] buckets;
        volatile int size;

        Stripe(int bucketCount) {
            buckets = new Node[bucketCount];
        }
    }

    private final Stripe[] stripes;

    public MysteriousHashMap() {
        this(256, 16);
    }

    public MysteriousHashMap(int initialCapacity, int stripeCount) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("initialCapacity must not be negative");
        }
        if (stripeCount <= 0) {
            throw new IllegalArgumentException("stripeCount must be positive");
        }

        stripes = new Stripe[stripeCount];
        long capacity = Math.max(1L, initialCapacity);
        int bucketsPerStripe = (int) Math.max(
                1L, (capacity + stripeCount - 1L) / stripeCount);

        for (int i = 0; i < stripeCount; i++) {
            stripes[i] = new Stripe(bucketsPerStripe);
        }
    }

    public boolean containsKey(K key) {
        requireKey(key);
        int hash = spread(key.hashCode());
        Stripe stripe = stripes[hash % stripes.length];
        Node[] buckets = stripe.buckets;

        for (Node node = buckets[hash % buckets.length]; node != null; node = node.next) {
            if (node.hash == hash && node.key.equals(key)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    public V get(K key) {
        requireKey(key);
        int hash = spread(key.hashCode());
        Stripe stripe = stripes[hash % stripes.length];
        Node[] buckets = stripe.buckets;

        for (Node node = buckets[hash % buckets.length]; node != null; node = node.next) {
            if (node.hash == hash && node.key.equals(key)) {
                return (V) node.value;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public V put(K key, V value) {
        requireKey(key);
        requireValue(value);

        int hash = spread(key.hashCode());
        Stripe stripe = stripes[hash % stripes.length];

        synchronized (stripe) {
            Node[] buckets = stripe.buckets;
            int bucketIndex = hash % buckets.length;
            Node head = buckets[bucketIndex];
            Node reversedPrefix = null;
            Node current = head;

            while (current != null && !(current.hash == hash && current.key.equals(key))) {
                reversedPrefix = new Node(
                        current.hash, current.key, current.value, reversedPrefix);
                current = current.next;
            }

            Node replacement;
            V previousValue = null;
            int newSize = stripe.size;

            if (current != null) {
                previousValue = (V) current.value;
                replacement = new Node(hash, key, value, current.next);
                for (Node node = reversedPrefix; node != null; node = node.next) {
                    replacement = new Node(
                            node.hash, node.key, node.value, replacement);
                }
            } else {
                replacement = new Node(hash, key, value, head);
                newSize++;
            }

            Node[] updated = buckets.clone();
            updated[bucketIndex] = replacement;

            if (current == null
                    && newSize > buckets.length - (buckets.length >>> 2)
                    && buckets.length <= Integer.MAX_VALUE / 2) {
                Node[] resized = new Node[buckets.length * 2];
                for (Node bucketHead : updated) {
                    for (Node node = bucketHead; node != null; node = node.next) {
                        int index = node.hash % resized.length;
                        resized[index] = new Node(
                                node.hash, node.key, node.value, resized[index]);
                    }
                }
                stripe.buckets = resized;
            } else {
                stripe.buckets = updated;
            }

            stripe.size = newSize;
            return previousValue;
        }
    }

    @SuppressWarnings("unchecked")
    public V remove(K key) {
        requireKey(key);

        int hash = spread(key.hashCode());
        Stripe stripe = stripes[hash % stripes.length];

        synchronized (stripe) {
            Node[] buckets = stripe.buckets;
            int bucketIndex = hash % buckets.length;
            Node current = buckets[bucketIndex];
            Node reversedPrefix = null;

            while (current != null && !(current.hash == hash && current.key.equals(key))) {
                reversedPrefix = new Node(
                        current.hash, current.key, current.value, reversedPrefix);
                current = current.next;
            }

            if (current == null) {
                return null;
            }

            Node replacement = current.next;
            for (Node node = reversedPrefix; node != null; node = node.next) {
                replacement = new Node(node.hash, node.key, node.value, replacement);
            }

            Node[] updated = buckets.clone();
            updated[bucketIndex] = replacement;
            stripe.buckets = updated;
            stripe.size--;
            return (V) current.value;
        }
    }

    public int size() {
        int total = 0;
        for (Stripe stripe : stripes) {
            synchronized (stripe) {
                total += stripe.size;
            }
        }
        return total;
    }

    @SuppressWarnings("unchecked")
    public void forEach(BiConsumer<K, V> consumer) {
        if (consumer == null) {
            throw new NullPointerException("consumer");
        }

        for (Stripe stripe : stripes) {
            Node[] buckets = stripe.buckets;
            for (Node head : buckets) {
                for (Node node = head; node != null; node = node.next) {
                    consumer.accept((K) node.key, (V) node.value);
                }
            }
        }
    }

    private static int spread(int hash) {
        return (hash ^ (hash >>> 16)) & 0x7fffffff;
    }

    private static void requireKey(Object key) {
        if (key == null) {
            throw new NullPointerException("key");
        }
    }

    private static void requireValue(Object value) {
        if (value == null) {
            throw new NullPointerException("value");
        }
    }
}

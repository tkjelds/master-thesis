@MysteriousHashMap.ThreadSafe
public class MysteriousHashMap<K, V> {
    @interface ThreadSafe {}

    private static final int STRIPE_COUNT = 16;
    private static final int INITIAL_BUCKET_COUNT = 16;

    @FunctionalInterface
    public interface BiConsumer<K, V> {
        void accept(K key, V value);
    }

    private static final class Node {
        final Object key;
        final Object value;
        final int hash;
        final Node next;

        Node(Object key, Object value, int hash, Node next) {
            this.key = key;
            this.value = value;
            this.hash = hash;
            this.next = next;
        }
    }

    private static final class Stripe {
        volatile Node[] buckets = new Node[INITIAL_BUCKET_COUNT];
        int size;
    }

    private final Stripe[] stripes = new Stripe[STRIPE_COUNT];

    public MysteriousHashMap() {
        for (int i = 0; i < stripes.length; i++) {
            stripes[i] = new Stripe();
        }
    }

    public boolean containsKey(K key) {
        int hash = hash(key);
        Stripe stripe = stripes[hash & (stripes.length - 1)];
        Node[] buckets = stripe.buckets;
        Node node = buckets[hash & (buckets.length - 1)];

        while (node != null) {
            if (node.hash == hash && key.equals(node.key)) {
                return true;
            }
            node = node.next;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    public V get(K key) {
        int hash = hash(key);
        Stripe stripe = stripes[hash & (stripes.length - 1)];
        Node[] buckets = stripe.buckets;
        Node node = buckets[hash & (buckets.length - 1)];

        while (node != null) {
            if (node.hash == hash && key.equals(node.key)) {
                return (V) node.value;
            }
            node = node.next;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public V put(K key, V value) {
        int hash = hash(key);
        Stripe stripe = stripes[hash & (stripes.length - 1)];

        synchronized (stripe) {
            Node[] buckets = stripe.buckets;
            int index = hash & (buckets.length - 1);
            Node head = buckets[index];
            Node matching = null;

            for (Node node = head; node != null; node = node.next) {
                if (node.hash == hash && key.equals(node.key)) {
                    matching = node;
                    break;
                }
            }

            if (matching != null) {
                Node newHead = null;
                for (Node node = head; node != null; node = node.next) {
                    if (node == matching) {
                        newHead = new Node(node.key, value, hash, newHead);
                    } else {
                        newHead = new Node(node.key, node.value, node.hash, newHead);
                    }
                }
                buckets[index] = newHead;
                return (V) matching.value;
            }

            buckets[index] = new Node(key, value, hash, head);
            stripe.size++;
            if (stripe.size > buckets.length - buckets.length / 4) {
                resize(stripe);
            }
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public V remove(K key) {
        int hash = hash(key);
        Stripe stripe = stripes[hash & (stripes.length - 1)];

        synchronized (stripe) {
            Node[] buckets = stripe.buckets;
            int index = hash & (buckets.length - 1);
            Node head = buckets[index];
            Node matching = null;

            for (Node node = head; node != null; node = node.next) {
                if (node.hash == hash && key.equals(node.key)) {
                    matching = node;
                    break;
                }
            }

            if (matching == null) {
                return null;
            }

            Node newHead = null;
            for (Node node = head; node != null; node = node.next) {
                if (node != matching) {
                    newHead = new Node(node.key, node.value, node.hash, newHead);
                }
            }
            buckets[index] = newHead;
            stripe.size--;
            return (V) matching.value;
        }
    }

    public int size() {
        long total = 0;
        for (Stripe stripe : stripes) {
            synchronized (stripe) {
                total += stripe.size;
            }
        }
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    @SuppressWarnings("unchecked")
    public void forEach(BiConsumer<K, V> consumer) {
        if (consumer == null) {
            throw new NullPointerException();
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

    private static int hash(Object key) {
        if (key == null) {
            throw new NullPointerException();
        }
        int hash = key.hashCode();
        return hash ^ (hash >>> 16);
    }

    private static void resize(Stripe stripe) {
        Node[] oldBuckets = stripe.buckets;
        if (oldBuckets.length >= (1 << 30)) {
            return;
        }

        Node[] newBuckets = new Node[oldBuckets.length << 1];
        int mask = newBuckets.length - 1;

        for (Node head : oldBuckets) {
            for (Node node = head; node != null; node = node.next) {
                int index = node.hash & mask;
                newBuckets[index] = new Node(node.key, node.value, node.hash, newBuckets[index]);
            }
        }

        stripe.buckets = newBuckets;
    }
}

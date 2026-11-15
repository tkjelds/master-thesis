import java.util.function.BiConsumer;

@MysteriousHashMap.ThreadSafe
class MysteriousHashMap<K, V> {
    @interface ThreadSafe {}

    private static final int DEFAULT_SIZE = 16;
    private static final float LOAD_FACTOR = 0.75f;

    private Stripe[] stripes;
    private int size;

    public MysteriousHashMap() {
        this.stripes = new Stripe[DEFAULT_SIZE];
        for (int i = 0; i < DEFAULT_SIZE; i++) {
            stripes[i] = new Stripe();
        }
    }

    private final class Stripe {
        private volatile Bucket[] buckets;
        private int size;

        public Stripe() {
            this.buckets = new Bucket[1];
            this.size = 0;
        }

        private int hash(Object key) {
            return Math.abs(key.hashCode() % buckets.length);
        }

        private boolean containsKey(K key) {
            int index = hash(key);
            Bucket bucket = buckets[index];
            if (bucket != null) {
                for (int i = 0; i < bucket.size(); i++) {
                    if (bucket.get(i).key.equals(key)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private V get(K key) {
            int index = hash(key);
            Bucket bucket = buckets[index];
            if (bucket != null) {
                for (int i = 0; i < bucket.size(); i++) {
                    if (bucket.get(i).key.equals(key)) {
                        return bucket.get(i).value;
                    }
                }
            }
            return null;
        }

        private V put(K key, V value) {
            int index = hash(key);
            Bucket bucket = buckets[index];
            synchronized (this) {
                if (bucket == null) {
                    bucket = new Bucket();
                    buckets[index] = bucket;
                }
                if (bucket.add(key, value)) {
                    size++;
                    rehashIfNecessary();
                    return null;
                } else {
                    return bucket.update(key, value);
                }
            }
        }

        private V remove(K key) {
            int index = hash(key);
            Bucket bucket = buckets[index];
            synchronized (this) {
                if (bucket != null) {
                    V oldValue = bucket.remove(key);
                    if (oldValue != null) {
                        size--;
                    }
                    return oldValue;
                }
                return null;
            }
        }

        private boolean add(K key, V value) {
            for (int i = 0; i < size(); i++) {
                if (get(i).key.equals(key)) {
                    return false;
                }
            }
           Bucket newBucket = new Bucket(size() + 1);
            for (int i = 0; i < size(); i++) {
                newBucket.add(get(i).key, get(i).value);
            }
            newBucket.add(key, value);
            buckets[index] = newBucket;
            return true;
        }

        private V update(K key, V value) {
            for (int i = 0; i < size(); i++) {
                if (get(i).key.equals(key)) {
                    V oldValue = get(i).value;
                    Bucket newBucket = new Bucket(size());
                    for (int j = 0; j < size(); j++) {
                        if (j != i) {
                            newBucket.add(get(j).key, get(j).value);
                        } else {
                            newBucket.add(key, value);
                        }
                    }
                    buckets[index] = newBucket;
                    return oldValue;
                }
            }
            return null;
        }

        private V remove(K key) {
            for (int i = 0; i < size(); i++) {
                if (get(i).key.equals(key)) {
                    V oldValue = get(i).value;
                    Bucket newBucket = new Bucket(size() - 1);
                    for (int j = 0; j < size(); j++) {
                        if (j != i) {
                            newBucket.add(get(j).key, get(j).value);
                        }
                    }
                    buckets[index] = newBucket;
                    return oldValue;
                }
            }
            return null;
        }

        private int size() {
            return size;
        }

        private void rehashIfNecessary() {
            if ((float) size / buckets.length > LOAD_FACTOR) {
                Bucket[] newBuckets = new Bucket[buckets.length * 2];
                for (int i = 0; i < buckets.length; i++) {
                    if (buckets[i] != null) {
                        for (int j = 0; j < buckets[i].size(); j++) {
                            int newIndex = Math.abs(buckets[i].get(j).key.hashCode() % newBuckets.length);
                            while (newBuckets[newIndex] != null) {
                                newIndex = Math.abs(newIndex + 1);
                            }
                            newBuckets[newIndex] = buckets[i].get(j).key;
                        }
                    }
                }
                buckets = newBuckets;
            }
        }

        private static final class Bucket {
            private final Node[] nodes;

            public Bucket() {
                this.nodes = new Node[0];
            }

            public boolean add(K key, V value) {
                Node newNode = new Node(key, value);
                for (int i = 0; i < size(); i++) {
                    if (get(i).key.equals(key)) {
                        return false;
                    }
                }
                Node[] newNodes = new Node[size() + 1];
                System.arraycopy(nodes, 0, newNodes, 0, size());
                newNodes[size()] = newNode;
                nodes = newNodes;
                return true;
            }

            public V update(K key, V value) {
                for (int i = 0; i < size(); i++) {
                    if (get(i).key.equals(key)) {
                        Node newNode = new Node(key, value);
                        Node[] newNodes = new Node[size()];
                        System.arraycopy(nodes, 0, newNodes, 0, size());
                        newNodes[i] = newNode;
                        nodes = newNodes;
                        return get(i).value;
                    }
                }
                return null;
            }

            public V remove(K key) {
                for (int i = 0; i < size(); i++) {
                    if (get(i).key.equals(key)) {
                        Node[] newNodes = new Node[size() - 1];
                        System.arraycopy(nodes, 0, newNodes, 0, i);
                        System.arraycopy(nodes, i + 1, newNodes, i, size() - i - 1);
                        nodes = newNodes;
                        return get(i).value;
                    }
                }
                return null;
            }

            public int size() {
                return nodes.length;
            }

            public Node get(int index) {
                return nodes[index];
            }
        }

        private static final class Node {
            K key;
            V value;

            public Node(K key, V value) {
                this.key = key;
                this.value = value;
            }
        }
    }

    public boolean containsKey(K k) {
        int index = Math.abs(k.hashCode() % stripes.length);
        return stripes[index].containsKey(k);
    }

    public V get(K k) {
        int index = Math.abs(k.hashCode() % stripes.length);
        return stripes[index].get(k);
    }

    public V put(K k, V v) {
        int index = Math.abs(k.hashCode() % stripes.length);
        return stripes[index].put(k, v);
    }

    public V remove(K k) {
        int index = Math.abs(k.hashCode() % stripes.length);
        return stripes[index].remove(k);
    }

    public int size() {
        synchronized (stripes) {
            int totalSize = 0;
            for (Stripe stripe : stripes) {
                totalSize += stripe.size();
            }
            return totalSize;
        }
    }

    public void forEach(BiConsumer<K, V> consumer) {
        for (Stripe stripe : stripes) {
            synchronized (stripe) {
                for (int i = 0; i < stripe.size(); i++) {
                    Node node = stripe.get(i);
                    consumer.accept(node.key, node.value);
                }
            }
        }
    }
}

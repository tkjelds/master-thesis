import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@BiConsumer.ThreadSafe
class BiConsumer<K, V> {
    @interface ThreadSafe {}

    public abstract void accept(K k, V v);
}

@MysteriousHashMap.ThreadSafe
class MysteriousHashMap<K, V> {
    @interface ThreadSafe {}

    private static final int DEFAULT_SIZE = 16;
    private static final float LOAD_FACTOR = 0.75f;

    private class Stripe {
        private List<Node<K, V>>[] buckets;
        private int size;

        @SuppressWarnings("unchecked")
        Stripe(int capacity) {
            this.buckets = (List<Node<K, V>>[]) new List[capacity];
            for (int i = 0; i < capacity; i++) {
                this.buckets[i] = new ArrayList<>();
            }
        }

        boolean containsKey(K key) {
            return buckets[getIndex(key)].stream()
                    .anyMatch(n -> n.key.equals(key));
        }

        V get(K key) {
            return buckets[getIndex(key)].stream()
                    .filter(n -> n.key.equals(key))
                    .findFirst()
                    .map(Node::getValue)
                    .orElse(null);
        }

        V put(K key, V value) {
            synchronized (buckets[getIndex(key)]) {
                int index = getIndex(key);
                Node<K, V> newNode = new Node<>(key, value);
                for (int i = 0; i < buckets[index].size(); i++) {
                    Node<K, V> node = buckets[index].get(i);
                    if (node.key.equals(key)) {
                        V oldValue = node.value;
                        buckets[index].set(i, newNode);
                        return oldValue;
                    }
                }
                buckets[index].add(newNode);
                size++;
                if ((double) size / buckets.length > LOAD_FACTOR) {
                    resize(index);
                }
                return null;
            }
        }

        V remove(K key) {
            synchronized (buckets[getIndex(key)]) {
                int index = getIndex(key);
                V oldValue = null;
                for (int i = 0; i < buckets[index].size(); i++) {
                    Node<K, V> node = buckets[index].get(i);
                    if (node.key.equals(key)) {
                        oldValue = node.value;
                        buckets[index].remove(i);
                        size--;
                        break;
                    }
                }
                return oldValue;
            }
        }

        private void resize(int index) {
            List<Node<K, V>>[] oldBuckets = buckets;
            int newSize = oldBuckets.length * 2;
            buckets = (List<Node<K, V>>[]) new List[newSize];
            for (int i = 0; i < newSize; i++) {
                buckets[i] = new ArrayList<>();
            }

            for (List<Node<K, V>> bucket : oldBuckets) {
                for (Node<K, V> node : bucket) {
                    buckets[getIndex(node.key)].add(new Node<>(node.key, node.value));
                }
            }
        }

        int size() {
            return size;
        }

        private int getIndex(K key) {
            return Math.abs(key.hashCode()) % buckets.length;
        }
    }

    private Stripe[] stripes;

    @SuppressWarnings("unchecked")
    public MysteriousHashMap() {
        this.stripes = (Stripe[]) new Stripe[DEFAULT_SIZE];
        for (int i = 0; i < DEFAULT_SIZE; i++) {
            this.stripes[i] = new Stripe(8);
        }
    }

    public boolean containsKey(K key) {
        return stripes[getStripeIndex(key)].containsKey(key);
    }

    public V get(K key) {
        return stripes[getStripeIndex(key)].get(key);
    }

    public V put(K key, V value) {
        return stripes[getStripeIndex(key)].put(key, value);
    }

    public V remove(K key) {
        return stripes[getStripeIndex(key)].remove(key);
    }

    public int size() {
        int totalSize = 0;
        for (Stripe stripe : stripes) {
            totalSize += stripe.size();
        }
        return totalSize;
    }

    public void forEach(BiConsumer<K, V> consumer) {
        for (Stripe stripe : stripes) {
            for (List<Node<K, V>> bucket : stripe.buckets) {
                for (Node<K, V> node : bucket) {
                    consumer.accept(node.key, node.value);
                }
            }
        }
    }

    private int getStripeIndex(K key) {
        return Math.abs(key.hashCode()) % stripes.length;
    }

    private static class Node<K, V> {
        K key;
        V value;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }

        K getKey() {
            return key;
        }

        V getValue() {
            return value;
        }
    }
}

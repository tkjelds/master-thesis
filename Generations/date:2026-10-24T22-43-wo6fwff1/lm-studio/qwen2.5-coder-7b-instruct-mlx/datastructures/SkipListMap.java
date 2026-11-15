```java
import java.util.concurrent.atomic.AtomicReference;

class Node<K extends Comparable<K>, V> {
    final K key;
    volatile V value;
    AtomicReference<Node<K, V>> next;

    Node(K key, V value) {
        this.key = key;
        this.value = value;
        next = new AtomicReference<>(null);
    }

    Node(K key) {
        this.key = key;
        next = new AtomicReference<>(null);
    }
}

public class SkipListMap<K extends Comparable<K>, V> {
    private static final double P_FACTOR = 0.5;
    private Node<K, V> head;

    public SkipListMap() {
        head = new Node<>(null);
    }

    private boolean coinFlip() {
        return Math.random() < P_FACTOR;
    }

    public V put(K key, V value) {
        Node<K, V> pred = head;
        for (int level = 15; level >= 0; --level) {
            while (true) {
                Node<K, V> curr = pred.next.get(level);
                if (curr != null && key.compareTo(curr.key) > 0) {
                    pred = curr;
                } else {
                    break;
                }
            }
        }

        Node<K, V> newNode = new Node<>(key, value);
        for (int level = 0; ; ++level) {
            while (true) {
                Node<K, V> curr = pred.next.get(level);
                if (curr != null && key.compareTo(curr.key) > 0) {
                    pred = curr;
                } else {
                    newNode.next.set(level, curr);
                    if (pred.next.compareAndSet(level, curr, newNode)) {
                        if (!coinFlip()) {
                            break;
                        } else if (level == 15) {
                            return null; // Full structure, cannot increase height
                        }
                    } else {
                        continue; // CAS failed, retry
                    }
                }
            }
        }
    }

    public V get(K key) {
        Node<K, V> pred = head;
        for (int level = 15; level >= 0; --level) {
            while (true) {
                Node<K, V> curr = pred.next.get(level);
                if (curr != null && key.compareTo(curr.key) > 0) {
                    pred = curr;
                } else if (curr != null && key.compareTo(curr.key) == 0) {
                    return curr.value;
                } else {
                    break;
                }
            }
        }
        return null; // Key not found
    }

    public V remove(K key) {
        Node<K, V> pred = head;
        for (int level = 15; level >= 0; --level) {
            while (true) {
                Node<K, V> curr = pred.next.get(level);
                if (curr != null && key.compareTo(curr.key) > 0) {
                    pred = curr;
                } else if (curr != null && key.compareTo(curr.key) == 0) {
                    awaitWriteCompletion(level);
                    return pred.next.getAndSet(level, curr.next.get(level)).value;
                } else {
                    break;
                }
            }
        }
        return null; // Key not found
    }

    private void awaitWriteCompletion(int level) {
        while (true) {
            Node<K, V> curr = head.next.get(level);
            if (curr == null) {
                return;
            }
            synchronized (curr) {
                if (curr.next.get(level) == null) {
                    return;
                }
            }
        }
    }

    public int size() {
        int count = 0;
        Node<K, V> curr = head.next.get(15);
        while (curr != null) {
            ++count;
            curr = curr.next.get(15);
        }
        return count;
    }

    public boolean isEmpty() {
        return size() == 0;
    }
}
```

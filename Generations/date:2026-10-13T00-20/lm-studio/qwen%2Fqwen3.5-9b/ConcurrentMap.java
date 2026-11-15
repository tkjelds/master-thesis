public class ConcurrentMap {

    private static class Node {
        Object key;
        Object value;
        Node next;

        Node(Object key, Object value) {
            this.key = key;
            this.value = value;
        }
    }

    private final int capacity;
    private int count;
    private final Node[] buckets;

    public ConcurrentMap() {
        this(10);
    }

    public ConcurrentMap(int capacity) {
        if (capacity <= 0) {
            this.capacity = 1;
        } else {
            this.capacity = capacity;
        }
        this.buckets = new Node[capacity];
        this.count = 0;
    }

    public Object put(Object key, Object value) {
        synchronized (this) {
            if (searchNode(key) != null) {
                Node node = searchNode(key);
                Object oldValue = node.value;
                node.value = value;
                return oldValue;
            }

            while (this.count >= this.capacity) {
                try {
                    this.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }

            Node newNode = new Node(key, value);
            addNode(buckets, newNode);
            this.count++;
            return null;
        }
    }

    private Node searchNode(Object key) {
        int hash = key == null ? 0 : key.hashCode();
        int index = normalizeIndex(hash);
        Node current = buckets[index];
        while (current != null) {
            if ((key == null ? current.key == null : key.equals(current.key))) {
                return current;
            }
            current = current.next;
        }
        return null;
    }

    private void addNode(Node[] buckets, Node newNode) {
        int hash = newNode.key == null ? 0 : newNode.key.hashCode();
        int index = normalizeIndex(hash);
        newNode.next = buckets[index];
        buckets[index] = newNode;
    }

    private Object get(Object key) {
        synchronized (this) {
            Node node = searchNode(key);
            return node == null ? null : node.value;
        }
    }

    public Object remove(Object key) {
        synchronized (this) {
            Node nodeToRemove = searchNode(key);
            if (nodeToRemove == null) {
                return null;
            }

            int hash = nodeToRemove.key == null ? 0 : nodeToRemove.key.hashCode();
            int index = normalizeIndex(hash);
            Node current = buckets[index];
            Node prev = null;

            while (current != null) {
                if ((key == null ? current.key == null : key.equals(current.key))) {
                    if (prev == null) {
                        buckets[index] = current.next;
                    } else {
                        prev.next = current.next;
                    }
                    this.count--;
                    this.notifyAll();
                    return nodeToRemove.value;
                }
                prev = current;
                current = current.next;
            }

            return null;
        }
    }

    public int size() {
        return this.count;
    }

    public boolean isEmpty() {
        return this.count == 0;
    }

    private int normalizeIndex(int hash) {
        int index = hash % this.capacity;
        if (index < 0) {
            index += this.capacity;
        }
        return index;
    }
}

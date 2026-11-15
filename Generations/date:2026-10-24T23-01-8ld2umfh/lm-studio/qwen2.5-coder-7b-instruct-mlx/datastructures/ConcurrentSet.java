@ConcurrentSet.ThreadSafe
public class ConcurrentSet {
    @interface ThreadSafe {}

    private final Node[] buckets;
    private final int capacity;

    public ConcurrentSet(int capacity) {
        this.capacity = capacity;
        this.buckets = new Node[capacity];
        for (int i = 0; i < capacity; i++) {
            buckets[i] = new Node();
        }
    }

    public boolean add(int value) {
        int index = hash(value);
        Node node = buckets[index];
        synchronized (node) {
            if (find(node, value)) {
                return false;
            } else {
                node.add(value);
                return true;
            }
        }
    }

    public boolean remove(int value) {
        int index = hash(value);
        Node node = buckets[index];
        synchronized (node) {
            if (!find(node, value)) {
                return false;
            } else {
                node.remove(value);
                return true;
            }
        }
    }

    public boolean contains(int value) {
        int index = hash(value);
        Node node = buckets[index];
        synchronized (node) {
            return find(node, value);
        }
    }

    private int hash(int value) {
        return Math.abs(value % capacity);
    }

    private boolean find(Node node, int value) {
        while (node != null) {
            if (node.value == value) {
                return true;
            }
            node = node.next;
        }
        return false;
    }

    private static class Node {
        int value;
        Node next;

        void add(int value) {
            if (this.value == 0) {
                this.value = value;
                return;
            }
            if (this.next == null) {
                this.next = new Node();
            }
            this.next.add(value);
        }

        void remove(int value) {
            if (this.value == value) {
                this.value = 0;
                return;
            }
            if (this.next != null) {
                this.next.remove(value);
            }
        }

        @Override
        public String toString() {
            if (this.value == 0) {
                return "";
            }
            if (this.next == null) {
                return String.valueOf(this.value);
            }
            return this.value + "," + this.next.toString();
        }
    }
}

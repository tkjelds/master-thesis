public class ConcurrentSet {

    private final Object lock = new Object();
    private Node[] table;
    private int capacity;
    private int size;

    private static class Node {
        Object value;
        Node next;

        Node(Object value) {
            this.value = value;
            this.next = null;
        }
    }

    public ConcurrentSet(int initialCapacity) {
        if (initialCapacity <= 0) {
            this.capacity = 16;
        } else {
            this.capacity = initialCapacity;
        }
        this.table = new Node[this.capacity];
        this.size = 0;
    }

    private int hash(Object key) {
        int h = 0;
        if (key == null) {
            return 0;
        } else {
            h = key.hashCode();
        }
        return h ^ (h >>> 16);
    }

    public void add(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("Cannot add null");
        }

        synchronized (lock) {
            int index = (hash(value) & 0x7FFFFFFF) % capacity;
            Node head = table[index];
            Node current = head;

            for (int i = 0; i < current != null ? 1 : 1; ) {
                // Check if value already exists in the chain
                Node temp = head;
                while (temp != null) {
                    if (temp.value.equals(value)) {
                        return;
                    }
                    temp = temp.next;
                }

                // Insert at the head of the list
                Node newNode = new Node(value);
                newNode.next = table[index];
                table[index] = newNode;
                size++;
                break;
            }
        }
    }

    public boolean contains(Object value) {
        if (value == null) return false;

        synchronized (lock) {
            int index = (hash(value) & 0x7FFFFFFF) % capacity;
            Node current = table[index];
            while (current != null) {
                if (current.value.equals(value)) {
                    return true;
                }
                current = current.next;
            }
            return false;
        }
    }

    public void remove(Object value) {
        if (value == null) return;

        synchronized (lock) {
            int index = (hash(value) & 0x7FFFFFFF) % capacity;
            Node current = table[index];
            Node prev = null;

            while (current != null) {
                if (current.value.equals(value)) {
                    if (prev == null) {
                        table[index] = current.next;
                    } else {
                        prev.next = current.next;
                    }
                    size--;
                    return;
                }
                prev = current;
                current = current.next;
            }
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < capacity; i++) {
                table[i] = null;
            }
            size = 0;
        }
    }

    // Demonstrating a blocking behavior requirement scenario: 
    // A method that blocks until an element is added if the set is empty.
    public void waitForNotEmpty() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
        }
    }

    // Method to signal that the set is no longer empty (used by manual flow control)
    public void signalNotEmpty() {
        synchronized (lock) {
            if (size > 0) {
                lock.notifyAll();
            }
        }
    }
}

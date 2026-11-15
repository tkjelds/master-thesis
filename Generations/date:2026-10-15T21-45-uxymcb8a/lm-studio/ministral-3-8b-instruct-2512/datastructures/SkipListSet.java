@SkipListSet.ThreadSafe
public class SkipListSet {
    @interface ThreadSafe {}

    private static final int MAX_LEVEL = 16;
    private static final double PROBABILITY = 0.5;

    private Node head;
    private int size;

    public SkipListSet() {
        this.head = new Node(MAX_LEVEL, null);
        this.size = 0;
    }

    public void add(int value) {
        Node[] update = new Node[MAX_LEVEL];
        Node current = head;

        for (int i = MAX_LEVEL - 1; i >= 0; i--) {
            while (current.forward[i] != null && current.forward[i].value < value) {
                current = current.forward[i];
            }
            update[i] = current;
        }

        current = current.forward[0];

        if (current == null || current.value != value) {
            int level = randomLevel();
            Node newNode = new Node(level, value);

            for (int i = 0; i < level; i++) {
                newNode.forward[i] = update[i].forward[i];
                update[i].forward[i] = newNode;
            }

            synchronized (this) {
                if (current == null || current.value != value) {
                    size++;
                }
            }
        }
    }

    public boolean contains(int value) {
        Node current = head;

        for (int i = MAX_LEVEL - 1; i >= 0; i--) {
            while (current.forward[i] != null && current.forward[i].value < value) {
                current = current.forward[i];
            }
        }

        current = current.forward[0];

        return current != null && current.value == value;
    }

    public boolean remove(int value) {
        Node[] update = new Node[MAX_LEVEL];
        Node current = head;

        for (int i = MAX_LEVEL - 1; i >= 0; i--) {
            while (current.forward[i] != null && current.forward[i].value < value) {
                current = current.forward[i];
            }
            update[i] = current;
        }

        current = current.forward[0];

        if (current != null && current.value == value) {
            for (int i = 0; i < MAX_LEVEL; i++) {
                if (update[i].forward[i] != current) break;
                update[i].forward[i] = current.forward[i];
            }

            synchronized (this) {
                size--;
                return true;
            }
        }

        return false;
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return size == 0;
        }
    }

    public void clear() {
        synchronized (this) {
            head = new Node(MAX_LEVEL, null);
            size = 0;
        }
    }

    private int randomLevel() {
        int level = 1;

        while (Math.random() < PROBABILITY && level < MAX_LEVEL) {
            level++;
        }

        return level;
    }

    private static class Node {
        volatile int value;
        volatile Node[] forward;

        Node(int level, int value) {
            this.value = value;
            this.forward = new Node[level];
        }
    }

    public void waitForSize(int expectedSize) {
        synchronized (this) {
            while (size != expectedSize) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    public void notifySizeChange() {
        synchronized (this) {
            notifyAll();
        }
    }

    public void waitForContains(int value, boolean expected) {
        synchronized (this) {
            while (!contains(value)) {
                if (expected && contains(value)) break;
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    public void notifyContainsChange() {
        synchronized (this) {
            notifyAll();
        }
    }

    public void waitForRemove(int value, boolean expected) {
        synchronized (this) {
            while (!remove(value)) {
                if (expected && remove(value)) break;
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    public void notifyRemoveChange() {
        synchronized (this) {
            notifyAll();
        }
    }
}

import java.util.Random;

// Node class for the skip list
@Node.ThreadSafe
class Node {
    @interface ThreadSafe {}

    int key;
    Node[] next;

    public Node(int key, int height) {
        this.key = key;
        this.next = new Node[height];
    }
}

// SkipListMap class implementing the concurrent skip list map
@SkipListMap.ThreadSafe
public class SkipListMap {
    @interface ThreadSafe {}

    private static final int MAX_LEVEL = 16; // Maximum level of the skip list
    private Node head;
    private int level;
    private Random random;

    public SkipListMap() {
        this.head = new Node(Integer.MIN_VALUE, MAX_LEVEL);
        this.level = 1;
        this.random = new Random();
    }

    // Insert a key-value pair into the skip list
    public synchronized void put(int key, int value) {
        Node[] update = new Node[MAX_LEVEL];
        Node current = head;
        
        for (int i = level - 1; i >= 0; i--) {
            while (current.next[i] != null && current.next[i].key < key) {
                current = current.next[i];
            }
            update[i] = current;
        }

        current = current.next[0];

        if (current == null || current.key != key) {
            int newLevel = randomLevel();
            if (newLevel > level) {
                for (int i = level; i < newLevel; i++) {
                    update[i] = head;
                }
                level = newLevel;
            }

            Node newNode = new Node(key, newLevel);
            for (int i = 0; i < newLevel; i++) {
                newNode.next[i] = update[i].next[i];
                update[i].next[i] = newNode;
            }
        } else {
            current.value = value; // Update the value if key already exists
        }
    }

    // Remove a key from the skip list
    public synchronized void remove(int key) {
        Node[] update = new Node[MAX_LEVEL];
        Node current = head;
        
        for (int i = level - 1; i >= 0; i--) {
            while (current.next[i] != null && current.next[i].key < key) {
                current = current.next[i];
            }
            update[i] = current;
        }

        current = current.next[0];

        if (current != null && current.key == key) {
            for (int i = 0; i < level; i++) {
                if (update[i].next[i] != current) {
                    break;
                }
                update[i].next[i] = current.next[i];
            }

            while (level > 1 && head.next[level - 1] == null) {
                level--;
            }
        }
    }

    // Get the value associated with a key from the skip list
    public synchronized Integer get(int key) {
        Node current = head;
        
        for (int i = level - 1; i >= 0; i--) {
            while (current.next[i] != null && current.next[i].key < key) {
                current = current.next[i];
            }
        }

        current = current.next[0];

        return (current != null && current.key == key) ? current.value : null;
    }

    // Generate a random level, with probability 1/4 for each level
    private int randomLevel() {
        int lvl = 0;
        while (lvl < MAX_LEVEL && random.nextInt(4) == 0) {
            lvl++;
        }
        return lvl;
    }

    // Node class for the skip list
    private class Node {
        int key;
        Integer value;
        Node[] next;

        public Node(int key, int height) {
            this.key = key;
            this.value = null;
            this.next = new Node[height];
        }
    }
}

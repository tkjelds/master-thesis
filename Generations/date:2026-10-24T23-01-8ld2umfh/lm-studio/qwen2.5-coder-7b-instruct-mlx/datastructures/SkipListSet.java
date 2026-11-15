import java.util.concurrent.atomic.AtomicInteger;

@Node.ThreadSafe
class Node {
    @interface ThreadSafe {}

    int value;
    volatile Node next;
    AtomicInteger height;

    Node(int value, int height) {
        this.value = value;
        this.next = null;
        this.height = new AtomicInteger(height);
    }
}

@SkipListSet.ThreadSafe
public class SkipListSet {
    @interface ThreadSafe {}

    private Node head;
    private int maxLevel;

    public SkipListSet() {
        this.head = new Node(Integer.MIN_VALUE, 32);
        this.maxLevel = 1;
    }

    private int randomLevel() {
        int lvl = 1;
        while (lvl < maxLevel && Math.random() < 0.5) {
            lvl++;
        }
        return lvl;
    }

    public synchronized boolean add(int value) {
        Node[] update = new Node[maxLevel];
        for (int i = 0; i < maxLevel; i++) {
            update[i] = head;
        }

        Node node = head;
        int lvl;
        for (lvl = maxLevel - 1; lvl >= 0; lvl--) {
            while (node.next != null && node.next.value < value) {
                node = node.next;
            }
            update[lvl] = node;
        }

        if (node.next != null && node.next.value == value) {
            return false;
        }

        int newLevel = randomLevel();
        if (newLevel > maxLevel) {
            for (int i = maxLevel; i < newLevel; i++) {
                update[i] = head;
            }
            maxLevel = newLevel;
        }

        node = new Node(value, newLevel);
        for (lvl = 0; lvl < newLevel; lvl++) {
            node.next = update[lvl].next;
            update[lvl].next = node;
        }

        return true;
    }

    public synchronized boolean remove(int value) {
        Node[] update = new Node[maxLevel];
        for (int i = 0; i < maxLevel; i++) {
            update[i] = head;
        }

        Node node = head;
        int lvl;
        for (lvl = maxLevel - 1; lvl >= 0; lvl--) {
            while (node.next != null && node.next.value < value) {
                node = node.next;
            }
            update[lvl] = node;
        }

        if (node.next == null || node.next.value != value) {
            return false;
        }

        for (lvl = 0; lvl < maxLevel; lvl++) {
            if (update[lvl].next != node) {
                break;
            }
            update[lvl].next = node.next;
        }

        while (maxLevel > 1 && head.next.height.get() == maxLevel - 1) {
            maxLevel--;
        }

        return true;
    }

    public synchronized boolean contains(int value) {
        Node node = head;
        for (int lvl = maxLevel - 1; lvl >= 0; lvl--) {
            while (node.next != null && node.next.value < value) {
                node = node.next;
            }
        }

        return node.next != null && node.next.value == value;
    }
}

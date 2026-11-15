public class ConcurrentSkipListMap {

    private static final int MAX_LEVEL = 16;
    private final Node head;
    private int size;

    public ConcurrentSkipListMap() {
        head = new Node(null, null, MAX_LEVEL);
        size = 0;
    }

    private static class Node {
        final Integer key;
        Object value;
        Node[] next;

        Node(Integer key, Object value, int level) {
            this.key = key;
            this.value = value;
            this.next = new Node[level];
        }
    }

    private static class RandomGenerator {
        private long seed;

        public RandomGenerator() {
            this.seed = System.currentTimeMillis();
        }

        public int nextInt(int n) {
            seed = (seed * 11964631 / 2500000001L) & 0x7fffffff;
            return (int) (seed % n);
        }
    }

    private final RandomGenerator rng = new RandomGenerator();

    public void put(Integer key, Object value) {
        Node prev[] = new Node[MAX_LEVEL];
        Node curr = head;

        for (int i = MAX_LEVEL - 1; i >= 0; i--) {
            while (curr.next[i] != null && curr.next[i].key.compareTo(key) < 0) {
                curr = curr.next[i];
            }
            prev[i] = curr;
        }

        curr = curr.next[0];

        synchronized (head) {
            if (curr != null && curr.key.equals(key)) {
                curr.value = value;
            } else {
                int level = 1;
                while (level < MAX_LEVEL && rng.nextInt(2) == 0) {
                    level++;
                }

                Node newNode = new Node(key, value, level);
                for (int i = 0; i < level; i++) {
                    newNode.next[i] = prev[i].next[i];
                    prev[i].next[i] = newNode;
                }
                size++;
            }
        }
    }

    public Object get(Integer key) {
        Node curr = head;
        for (int i = MAX_LEVEL - 1; i >= 0; i--) {
            while (curr.next[i] != null && curr.next[i].key.compareTo(key) < 0) {
                curr = curr.next[i];
            }
        }
        Node target = curr.next[0];

        if (target != null && target.key.equals(key)) {
            return target.value;
        }
        return null;
    }

    public int size() {
        synchronized (head) {
            return size;
        }
    }

    public boolean remove(Integer key) {
        Node prev[] = new Node[MAX_LEVEL];
        Node curr = head;

        for (int i = MAX_LEVEL - 1; i >= 0; i--) {
            while (curr.next[i] != null && curr.next[i].key.compareTo(key) < 0) {
                curr = curr.next[i];
            }
            prev[i] = curr;
        }

        curr = curr.next[0];

        synchronized (head) {
            if (curr != null && curr.key.equals(key)) {
                for (int i = 0; i < MAX_LEVEL; i++) {
                    if (prev[i].next[i] != curr) break;
                    prev[i].next[i] = curr.next[i];
                }
                size--;
                return true;
            }
        }
        return false;
    }

    public static void main(String[] args) {
        ConcurrentSkipListMap map = new ConcurrentSkipListMap();

        Thread writer = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                map.put(i, "Value" + i);
            }
        });

        Thread reader = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                map.get(i);
            }
        });

        writer.start();
        reader.start();

        try {
            writer.join();
            reader.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        System.out.println("Final size: " + map.size());
    }
}

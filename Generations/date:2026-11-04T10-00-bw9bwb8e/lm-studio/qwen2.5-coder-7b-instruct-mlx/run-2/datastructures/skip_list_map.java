@SkipListNode.ThreadSafe
class SkipListNode<K extends Comparable<K>, V> {
    @interface ThreadSafe {}

    K key;
    V value;
    SkipListNode<K, V>[] next;

    @SuppressWarnings("unchecked")
    public SkipListNode(int level) {
        this.next = new SkipListNode[level];
    }
}

@SkipListMap.ThreadSafe
class SkipListMap<K extends Comparable<K>, V> {
    @interface ThreadSafe {}

    private static final int MAX_LEVEL = 32;
    private static final double P_FACTOR = 0.5;
    SkipListNode<K, V> head;
    int level;

    @SuppressWarnings("unchecked")
    public SkipListMap() {
        this.head = new SkipListNode<>(MAX_LEVEL);
        for (int i = 0; i < MAX_LEVEL; ++i) {
            head.next[i] = null;
        }
        this.level = 1;
    }

    private int randomLevel() {
        int lvl = 1;
        while (lvl < MAX_LEVEL && Math.random() < P_FACTOR) {
            ++lvl;
        }
        return lvl;
    }

    @SuppressWarnings("unchecked")
    public synchronized V put(K key, V value) {
        SkipListNode<K, V>[] update = new SkipListNode[MAX_LEVEL];
        SkipListNode<K, V> current = head;

        for (int i = level - 1; i >= 0; --i) {
            while (current.next[i] != null && current.next[i].key.compareTo(key) < 0) {
                current = current.next[i];
            }
            update[i] = current;
        }

        current = current.next[0];

        if (current != null && current.key.compareTo(key) == 0) {
            V oldValue = current.value;
            current.value = value;
            return oldValue;
        }

        int newLevel = randomLevel();
        if (newLevel > level) {
            for (int i = level; i < newLevel; ++i) {
                update[i] = head;
            }
            level = newLevel;
        }

        @SuppressWarnings("unchecked")
        SkipListNode<K, V> newNode = new SkipListNode<>(newLevel);
        newNode.key = key;
        newNode.value = value;

        for (int i = 0; i < newLevel; ++i) {
            newNode.next[i] = update[i].next[i];
            update[i].next[i] = newNode;
        }

        return null;
    }

    public synchronized V get(K key) {
        SkipListNode<K, V> current = head;

        for (int i = level - 1; i >= 0; --i) {
            while (current.next[i] != null && current.next[i].key.compareTo(key) < 0) {
                current = current.next[i];
            }
        }

        current = current.next[0];

        if (current != null && current.key.compareTo(key) == 0) {
            return current.value;
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    public synchronized V remove(K key) {
        SkipListNode<K, V>[] update = new SkipListNode[MAX_LEVEL];
        SkipListNode<K, V> current = head;

        for (int i = level - 1; i >= 0; --i) {
            while (current.next[i] != null && current.next[i].key.compareTo(key) < 0) {
                current = current.next[i];
            }
            update[i] = current;
        }

        current = current.next[0];

        if (current != null && current.key.compareTo(key) == 0) {
            V oldValue = current.value;

            for (int i = 0; i < level; ++i) {
                if (update[i].next[i] != current) {
                    break;
                }
                update[i].next[i] = current.next[i];
            }

            while (level > 1 && head.next[level - 1] == null) {
                --level;
            }

            return oldValue;
        }

        return null;
    }
}

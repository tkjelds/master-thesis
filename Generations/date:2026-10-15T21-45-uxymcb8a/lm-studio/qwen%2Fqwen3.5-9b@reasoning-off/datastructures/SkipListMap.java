@SkipListMap.ThreadSafe
public class SkipListMap {
    @interface ThreadSafe {}


    private static final int MAX_LEVEL = 16;
    private static final double P = 0.5;

    private class Node {
        private final Object key;
        private final Object value;
        private int level;
        private Node[] forward;

        private Node(Object key, Object value, int level) {
            this.key = key;
            this.value = value;
            this.level = level;
            this.forward = new Node[level + 1];
        }
    }

    private volatile int headerLevel;
    private volatile Node header;
    private volatile boolean closed;

    public SkipListMap() {
        this.header = new Node(null, null, MAX_LEVEL);
        this.headerLevel = 0;
    }

    public synchronized Object put(Object key, Object value) {
        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("SkipListMap is closed");
            }
            insert(key, value);
            return value;
        }
    }

    public synchronized Object get(Object key) {
        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("SkipListMap is closed");
            }
            Node x = header;
            for (int i = headerLevel - 1; i >= 0; i--) {
                while (x.forward[i] != null && keyToCompare(x.forward[i].key) < key) {
                    x = x.forward[i];
                }
            }
            Node y = x;
            for (int i = 0; i < headerLevel; i++) {
                Node z = y.forward[i];
                if (z != null) {
                    y = z;
                } else {
                    y.forward[i] = z;
                }
            }
            if (y.forward[0] == key) {
                return y.forward[1];
            }
        }
        return null;
    }

    public synchronized void remove(Object key) {
        synchronized (this) {
            if (closed) {
                return;
            }
            delete(key);
        }
    }

    public synchronized boolean isEmpty() {
        synchronized (this) {
            if (closed) {
                return true;
            }
            return header.forward[0] == null;
        }
    }

    public synchronized boolean contains(Object key) {
        synchronized (this) {
            if (closed) {
                return false;
            }
            return findNode(key) != null;
        }
    }

    public synchronized void close() {
        synchronized (this) {
            if (!closed) {
                closed = true;
                notifyAll();
            }
        }
    }

    public synchronized void waitUntilOpen() throws InterruptedException {
        synchronized (this) {
            while (closed) {
                wait();
            }
        }
    }

    public synchronized void ensureCapacity(int min) {
        synchronized (this) {
            if (closed) {
                return;
            }
            if (headerLevel < MAX_LEVEL) {
                // Force a level bump to maintain structure even if no insertions recently
                // In a real lock-free implementation, this would be probabilistic or use a counter
                // Here we simulate a simple maintenance or just return as level is managed in insert/delete
                // We do nothing specific but ensure the block is entered safely.
            }
        }
    }

    private void insert(Object key, Object value) {
        boolean shouldInsert = false;
        Node x = header;
        Node update[] = new Node[MAX_LEVEL + 1];
        update[0] = header;

        for (int i = headerLevel - 1; i >= 0; i--) {
            while (x.forward[i] != null && keyToCompare(x.forward[i].key, key) < 0) {
                update[i + 1] = x;
                x = x.forward[i];
            }
            update[i] = x;
        }

        Node y = update[headerLevel];
        Node z = null;

        int cmp = keyToCompare(y == null ? null : y.key, key);
        if (cmp == 0) {
            update[y.level].value = value;
            shouldInsert = false;
        } else {
            shouldInsert = true;
        }

        int k = (shouldInsert && isRandom() && Math.random() < P) ? 1 : 0;
        if (k > 0) {
            k++;
        }
        while (k < MAX_LEVEL) {
            k++;
        }

        Node newNode = new Node(key, value, k);
        headerLevel = Math.max(headerLevel, k);

        Node next = update[k];
        for (int i = update.length - 1; i > 0; i--) {
            next = update[i];
            newNode.forward[i] = next.forward[i];
            if (x.forward[i] != null && keyToCompare(x.forward[i].key, key) >= 0) {
                x.forward[i] = newNode;
            } else if (next.forward[i] != null) {
                next.forward[i] = newNode;
            }
        }

        Node prev = update[0];
        if (shouldInsert) {
            prev.forward[0] = newNode;
        }
    }

    private void delete(Object key) {
        synchronized (this) {
            Node x = header;
            Node[] update = new Node[MAX_LEVEL + 1];
            update[0] = header;

            for (int i = headerLevel - 1; i >= 0; i--) {
                while (x.forward[i] != null && keyToCompare(x.forward[i].key, key) < 0) {
                    update[i + 1] = x;
                    x = x.forward[i];
                }
                update[i] = x;
            }

            Node y = update[headerLevel];
            Node z = null;

            int cmp = keyToCompare(y == null ? null : y.key, key);
            if (cmp == 0) {
                z = y;
                if (update[headerLevel] != null && z == update[headerLevel]) {
                    if (headerLevel == MAX_LEVEL) {
                        while (update[MAX_LEVEL] == null) {
                            update[update.length - 1] = update[update.length - 2].forward[update.length - 2];
                            update[MAX_LEVEL] = x;
                            x = update[MAX_LEVEL];
                            if (update[headerLevel] == null) {
                                headerLevel--;
                            } else {
                                x = x.forward[headerLevel];
                            }
                            break;
                        }
                    } else {
                        headerLevel++;
                    }
                }

                if (update[0].forward[0] == z) {
                    update[0] = update[0].forward[1];
                } else {
                    Node next = z.forward[0];
                    update[0].forward[0] = next;
                }

                for (int i = 1; i < update.length; i++) {
                    if (update[i] == null || update[i].forward[0] != z) {
                        if (update[i] != null && next == update[i].forward[0]) {
                            update[i].forward[i] = next;
                        } else {
                            update[i] = z;
                        }
                    }
                }
                
                // Update levels for all nodes in the path up to the last found node
                for (int i = 0; i < update.length; i++) {
                    while (update[i].level >= i && 
                           (update[i].forward[i] == z || update[i].forward[i] == null)) {
                        update[i] = update[i].forward[i];
                    }
                    if (update[i] == null) break;
                }

                for (int i = 0; i < update.length; i++) {
                    Node nextNode = update[i];
                    if (nextNode == null) {
                        if (headerLevel == 0 && update[i].forward[0] == null) {
                            headerLevel = i;
                        } else {
                            nextNode.forward[i] = update[i + 1];
                            if (i == 0) {
                                if (update[0] == null) {
                                    header = nextNode;
                                }
                            } else {
                                update[i - 1].forward[i] = nextNode.forward[i];
                            }
                        }
                    }
                }
            }
        }
    }

    private int keyToCompare(Object key1, Object key2) {
        if (key1 == null) {
            if (key2 == null) return 0;
            return -1;
        }
        if (key2 == null) return 1;
        int result = key1.compareTo(key2);
        if (result < 0) return -1;
        if (result > 0) return 1;
        return 0;
    }

    private int keyToCompare(Object key1, Object key2) {
        if (key1 == null) {
            if (key2 == null) return 0;
            return -1;
        }
        if (key2 == null) return 1;
        int result = key1.compareTo(key2);
        if (result < 0) return -1;
        if (result > 0) return 1;
        return 0;
    }

    private boolean isRandom() {
        return Math.random() < P;
    }

    private Node findNode(Object key) {
        Node x = header;
        for (int i = headerLevel - 1; i >= 0; i--) {
            while (x.forward[i] != null && keyToCompare(x.forward[i].key, key) < 0) {
                x = x.forward[i];
            }
        }
        return x;
    }

    private int keyToCompare(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        return a.compareTo(b);
    }
}

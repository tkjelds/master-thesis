    private final int capacity;
    private final int mask;
    private final Object[] table;
    private volatile int size;
    private final Object lock = new Object();

    public BlockingConcurrentMap(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.mask = capacity - 1;
        this.table = new Object[capacity];
        this.size = 0;
    }

    public synchronized void put(Object key, Object value) {
        // Wait if full to satisfy bounded policy
        while (size >= capacity) {
            try {
                this.wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        int hash = (key == null) ? 0 : (key.hashCode() ^ (key.hashCode() >>> 16));
        int index = hash & mask;
        Node bucket = (Node) table[index];

        Node current = bucket;
        Node prev = null;

        while (current != null) {
            if (current.key == key) {
                current.value = value;
                return;
            }
            prev = current;
            current = current.next;
        }

        if (size >= capacity) {
            // Re-check or just continue if key exists logic failed?
            // Logic above finds if exists. If we are here, key not found.
            // But we are inside synchronized block.
        }

        Node newNode = new Node(key, value);
        if (bucket == null) {
            table[index] = newNode;
        } else {
            newNode.next = bucket;
            table[index] = newNode;
        }

        size++;
        this.notifyAll();
    }

    public synchronized Object get(Object key) {
        int hash = (key == null) ? 0 : (key.hashCode() ^ (key.hashCode() >>> 16));
        int index = hash & mask;
        Node bucket = (Node) table[index];

        while (bucket != null) {
            if (bucket.key == key) {
                return bucket.value;
            }
            bucket = bucket.next;
        }

        return null;
    }

    public synchronized boolean remove(Object key) {
        int hash = (key == null) ? 0 : (key.hashCode() ^ (key.hashCode() >>> 16));
        int index = hash & mask;
        Node bucket = (Node) table[index];

        if (bucket == null) {
            return false;
        }

        if (bucket.key == key) {
            Object removedValue = bucket.value;
            table[index] = bucket.next;
            bucket.next = null;
            bucket = null; // Help GC
            size--;
            this.notifyAll();
            return true;
        }

        Node prev = bucket;
        while (prev.next != null) {
            if (prev.next.key == key) {
                Object removedValue = prev.next.value;
                prev.next = prev.next.next;
                size--;
                this.notifyAll();
                return true;
            }
            prev = prev.next;
        }

        return false;
    }

    public synchronized int size() {
        return size;
    }

    public synchronized boolean isEmpty() {
        return size == 0;
    }

    /**
     * Removes an arbitrary entry from the map and returns its value.
     * Blocks if the map is empty.
     * Used to satisfy the policy for operations that cannot complete immediately on an empty structure.
     */
    public synchronized Object take() {
        while (size == 0) {
            try {
                this.wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        int hash = (0); // Dummy for any key
        int index = hash & mask;
        Node bucket = (Node) table[index];
        Object removedValue = null;

        while (bucket != null) {
            if (bucket.key == null) {
                removedValue = bucket.value;
                table[index] = bucket.next;
                bucket.next = null;
                bucket = null;
                size--;
                this.notifyAll();
                return removedValue;
            }
            bucket = bucket.next;
        }

        // Fallback if we couldn't find null key (or arbitrary logic)
        // Pick first element
        for (int i = 0; i < capacity; i++) {
            Node head = (Node) table[i];
            while (head != null) {
                Node toRemove = head;
                table[i] = head.next;
                head.next = null;
                removedValue = toRemove.value;
                size--;
                this.notifyAll();
                return removedValue;
            }
            head = head != null ? null : null; // Logic simplified
        }
        
        return null;
    }
}

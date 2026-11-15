@TaggedCapacityBuffer.ThreadSafe
public class TaggedCapacityBuffer {
    @interface ThreadSafe {}


    private static final Object REGISTRY = new Object();
    private static long nextOrder = 1L;
    private static long nextMessageId = 1L;

    private static final class Node {
        final long id;
        final byte[] payload;
        final String[] tags;
        Node prev;
        Node next;

        Node(long id, byte[] payload, String[] tags) {
            this.id = id;
            this.payload = payload;
            this.tags = tags;
        }
    }

    public static final class Message {
        private final long id;
        private final byte[] payload;
        private final String[] tags;

        private Message(long id, byte[] payload, String[] tags) {
            this.id = id;
            this.payload = payload;
            this.tags = tags;
        }

        public long getId() {
            return id;
        }

        public byte[] getPayload() {
            return payload.clone();
        }

        public String[] getTags() {
            return tags.clone();
        }
    }

    public static final class Snapshot {
        private final Message[] messages;
        private final int occupancy;
        private final int capacity;
        private final boolean closed;

        private Snapshot(Message[] messages, int occupancy, int capacity, boolean closed) {
            this.messages = messages;
            this.occupancy = occupancy;
            this.capacity = capacity;
            this.closed = closed;
        }

        public Message[] getMessages() {
            return messages.clone();
        }

        public int getOccupancy() {
            return occupancy;
        }

        public int getCapacity() {
            return capacity;
        }

        public boolean isClosed() {
            return closed;
        }
    }

    private final int capacity;
    private final long order;

    // All fields below are guarded by "this".
    private int occupancy;
    private boolean closed;
    private Node head;
    private Node tail;

    public TaggedCapacityBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        synchronized (REGISTRY) {
            this.order = nextOrder++;
        }
    }

    private static byte[] copyPayload(byte[] payload) {
        if (payload == null) {
            throw new NullPointerException("payload");
        }
        if (payload.length == 0) {
            throw new IllegalArgumentException("payload must not be empty");
        }
        return payload.clone();
    }

    private static String[] copyTags(String[] tags) {
        if (tags == null) {
            throw new NullPointerException("tags");
        }
        String[] copy = new String[tags.length];
        for (int i = 0; i < tags.length; i++) {
            if (tags[i] == null) {
                throw new NullPointerException("null tag element");
            }
            copy[i] = tags[i];
        }
        return copy;
    }

    private static long allocateId() {
        synchronized (REGISTRY) {
            return nextMessageId++;
        }
    }

    private static boolean hasAnyTag(String[] tags, String[] wanted) {
        for (String t : tags) {
            for (String w : wanted) {
                if (t.equals(w)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasTag(String[] tags, String wanted) {
        for (String t : tags) {
            if (t.equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    // Caller must hold this monitor.
    private Node findOldestWithAnyTag(String[] wanted) {
        Node cur = head;
        while (cur != null) {
            if (hasAnyTag(cur.tags, wanted)) {
                return cur;
            }
            cur = cur.next;
        }
        return null;
    }

    // Caller must hold this monitor.
    private Node findOldestWithTag(String wanted) {
        Node cur = head;
        while (cur != null) {
            if (hasTag(cur.tags, wanted)) {
                return cur;
            }
            cur = cur.next;
        }
        return null;
    }

    // Caller must hold this monitor. Node must be linked in this buffer.
    private void unlink(Node n) {
        if (n.prev != null) {
            n.prev.next = n.next;
        } else {
            head = n.next;
        }
        if (n.next != null) {
            n.next.prev = n.prev;
        } else {
            tail = n.prev;
        }
        n.prev = null;
        n.next = null;
        occupancy -= n.payload.length;
    }

    // Caller must hold this monitor.
    private void append(Node n) {
        n.prev = tail;
        n.next = null;
        if (tail != null) {
            tail.next = n;
        } else {
            head = n;
        }
        tail = n;
        occupancy += n.payload.length;
    }

    /**
     * Nonblocking insert. Evicts oldest messages sharing an eviction tag first,
     * then the oldest messages overall, until the new message fits.
     */
    public long offer(byte[] payload, String[] tags, String[] evictionTags) {
        byte[] p = copyPayload(payload);
        String[] t = copyTags(tags);
        String[] e = copyTags(evictionTags);
        if (p.length > capacity) {
            throw new IllegalArgumentException("payload exceeds capacity");
        }
        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("buffer is closed");
            }
            int len = p.length;
            while (occupancy + len > capacity) {
                Node victim = findOldestWithAnyTag(e);
                if (victim == null) {
                    victim = head; // non-null since occupancy > 0 here
                }
                unlink(victim);
            }
            long id = allocateId();
            append(new Node(id, p, t));
            notifyAll();
            return id;
        }
    }

    /**
     * Blocking insert. Waits interruptibly for real capacity; never evicts.
     */
    public long putBlocking(byte[] payload, String[] tags) throws InterruptedException {
        byte[] p = copyPayload(payload);
        String[] t = copyTags(tags);
        if (p.length > capacity) {
            throw new IllegalArgumentException("payload exceeds capacity");
        }
        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("buffer is closed");
            }
            while (!closed && occupancy + p.length > capacity) {
                wait();
            }
            if (closed) {
                throw new IllegalStateException("buffer closed while waiting");
            }
            long id = allocateId();
            append(new Node(id, p, t));
            notifyAll();
            return id;
        }
    }

    /**
     * Waits interruptibly for the oldest message bearing the exact tag, removes it,
     * and returns it detached. Returns null if closed and no matching message remains.
     */
    public Message takeMatching(String tag) throws InterruptedException {
        if (tag == null) {
            throw new NullPointerException("tag");
        }
        synchronized (this) {
            while (true) {
                Node n = findOldestWithTag(tag);
                if (n != null) {
                    unlink(n);
                    notifyAll();
                    return new Message(n.id, n.payload, n.tags);
                }
                if (closed) {
                    return null;
                }
                wait();
            }
        }
    }

    /**
     * Atomically moves this buffer's oldest message bearing tag to destination.
     * Returns false without mutation if no match, either buffer is closed, or
     * destination lacks capacity. Never evicts or waits.
     */
    public boolean moveTo(TaggedCapacityBuffer destination, String tag) {
        if (destination == null) {
            throw new NullPointerException("destination");
        }
        if (tag == null) {
            throw new NullPointerException("tag");
        }
        if (destination == this) {
            throw new IllegalArgumentException("destination must differ from source");
        }
        TaggedCapacityBuffer first = this.order < destination.order ? this : destination;
        TaggedCapacityBuffer second = (first == this) ? destination : this;
        synchronized (first) {
            synchronized (second) {
                if (closed || destination.closed) {
                    return false;
                }
                Node n = findOldestWithTag(tag);
                if (n == null) {
                    return false;
                }
                if (destination.occupancy + n.payload.length > destination.capacity) {
                    return false;
                }
                unlink(n);
                destination.append(n);
                this.notifyAll();
                destination.notifyAll();
                return true;
            }
        }
    }

    /**
     * Rejects future insertions and wakes all waiters. Existing messages may drain.
     */
    public void close() {
        synchronized (this) {
            closed = true;
            notifyAll();
        }
    }

    /**
     * Returns a coherent detached snapshot of messages in FIFO order.
     */
    public Snapshot snapshot() {
        synchronized (this) {
            int n = 0;
            for (Node cur = head; cur != null; cur = cur.next) {
                n++;
            }
            Message[] msgs = new Message[n];
            int i = 0;
            for (Node cur = head; cur != null; cur = cur.next) {
                msgs[i++] = new Message(cur.id, cur.payload.clone(), cur.tags.clone());
            }
            return new Snapshot(msgs, occupancy, capacity, closed);
        }
    }
}

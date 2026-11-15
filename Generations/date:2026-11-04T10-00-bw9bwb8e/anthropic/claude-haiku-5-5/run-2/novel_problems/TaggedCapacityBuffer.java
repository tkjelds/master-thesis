@TaggedCapacityBuffer.ThreadSafe
public class TaggedCapacityBuffer {
    @interface ThreadSafe {}


    private static final Object ID_LOCK = new Object();
    private static long nextMessageId = 1L;
    private static long nextBufferOrder = 1L;

    private final long order;
    private final long capacity;
    private final Object lock = new Object();

    // Guarded by lock
    private Node head;
    private Node tail;
    private long occupancy;
    private int count;
    private boolean closed;

    public TaggedCapacityBuffer(long capacity) {
        if (capacity <= 0L) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        synchronized (ID_LOCK) {
            this.order = nextBufferOrder++;
        }
    }

    public long offer(byte[] payload, String[] tags, String[] evictionTags) {
        byte[] payloadCopy = copyPayload(payload);
        String[] tagsCopy = copyTags(tags);
        String[] evictionCopy = copyTags(evictionTags);
        int len = payloadCopy.length;

        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("buffer is closed");
            }
            while (occupancy + len > capacity) {
                Node victim = findEvictionVictim(evictionCopy);
                removeNode(victim);
            }
            long id = allocateId();
            Message m = new Message(id, payloadCopy, tagsCopy);
            appendNode(m);
            lock.notifyAll();
            return id;
        }
    }

    public long putBlocking(byte[] payload, String[] tags) throws InterruptedException {
        byte[] payloadCopy = copyPayload(payload);
        String[] tagsCopy = copyTags(tags);
        int len = payloadCopy.length;

        synchronized (lock) {
            while (!closed && occupancy + len > capacity) {
                lock.wait();
            }
            if (closed) {
                throw new IllegalStateException("buffer is closed");
            }
            long id = allocateId();
            Message m = new Message(id, payloadCopy, tagsCopy);
            appendNode(m);
            lock.notifyAll();
            return id;
        }
    }

    public Message takeMatching(String tag) throws InterruptedException {
        if (tag == null) {
            throw new NullPointerException("tag");
        }
        synchronized (lock) {
            while (true) {
                Node n = findFirst(tag);
                if (n != null) {
                    removeNode(n);
                    lock.notifyAll();
                    return n.message;
                }
                if (closed) {
                    return null;
                }
                lock.wait();
            }
        }
    }

    public boolean moveTo(TaggedCapacityBuffer destination, String tag) {
        if (destination == null) {
            throw new NullPointerException("destination");
        }
        if (tag == null) {
            throw new NullPointerException("tag");
        }
        if (destination == this) {
            throw new IllegalArgumentException("cannot move to self");
        }

        TaggedCapacityBuffer first = this.order < destination.order ? this : destination;
        TaggedCapacityBuffer second = first == this ? destination : this;

        synchronized (first.lock) {
            synchronized (second.lock) {
                if (this.closed || destination.closed) {
                    return false;
                }
                Node n = findFirst(tag);
                if (n == null) {
                    return false;
                }
                long len = n.message.payload.length;
                if (destination.occupancy + len > destination.capacity) {
                    return false;
                }
                removeNode(n);
                destination.appendExisting(n.message);
                this.lock.notifyAll();
                destination.lock.notifyAll();
                return true;
            }
        }
    }

    public void close() {
        synchronized (lock) {
            closed = true;
            lock.notifyAll();
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            Message[] arr = new Message[count];
            int i = 0;
            for (Node n = head; n != null; n = n.next) {
                arr[i++] = n.message;
            }
            return new Snapshot(arr, occupancy, capacity, closed);
        }
    }

    // ---- internal helpers (caller must hold the relevant lock) ----

    private long allocateId() {
        synchronized (ID_LOCK) {
            return nextMessageId++;
        }
    }

    private void appendNode(Message m) {
        Node node = new Node(m);
        if (tail == null) {
            head = node;
            tail = node;
        } else {
            tail.next = node;
            node.prev = tail;
            tail = node;
        }
        occupancy += m.payload.length;
        count++;
    }

    private void appendExisting(Message m) {
        appendNode(m);
    }

    private void removeNode(Node n) {
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
        occupancy -= n.message.payload.length;
        count--;
    }

    private Node findFirst(String tag) {
        for (Node n = head; n != null; n = n.next) {
            if (contains(n.message.tags, tag)) {
                return n;
            }
        }
        return null;
    }

    private Node findEvictionVictim(String[] evictionTags) {
        for (Node n = head; n != null; n = n.next) {
            for (String t : evictionTags) {
                if (contains(n.message.tags, t)) {
                    return n;
                }
            }
        }
        return head;
    }

    private static boolean contains(String[] tags, String tag) {
        for (String t : tags) {
            if (t.equals(tag)) {
                return true;
            }
        }
        return false;
    }

    private byte[] copyPayload(byte[] payload) {
        if (payload == null) {
            throw new NullPointerException("payload");
        }
        if (payload.length == 0) {
            throw new IllegalArgumentException("payload must not be empty");
        }
        if (payload.length > capacity) {
            throw new IllegalArgumentException("payload exceeds capacity");
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
                throw new NullPointerException("tag element");
            }
            copy[i] = tags[i];
        }
        return copy;
    }

    // ---- nested types ----

    private static final class Node {
        final Message message;
        Node prev;
        Node next;

        Node(Message message) {
            this.message = message;
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
            String[] copy = new String[tags.length];
            for (int i = 0; i < tags.length; i++) {
                copy[i] = tags[i];
            }
            return copy;
        }
    }

    public static final class Snapshot {
        private final Message[] messages;
        private final long occupancy;
        private final long capacity;
        private final boolean closed;

        private Snapshot(Message[] messages, long occupancy, long capacity, boolean closed) {
            this.messages = messages;
            this.occupancy = occupancy;
            this.capacity = capacity;
            this.closed = closed;
        }

        public Message[] getMessages() {
            Message[] copy = new Message[messages.length];
            for (int i = 0; i < messages.length; i++) {
                copy[i] = messages[i];
            }
            return copy;
        }

        public long getOccupancy() {
            return occupancy;
        }

        public long getCapacity() {
            return capacity;
        }

        public boolean isClosed() {
            return closed;
        }
    }
}

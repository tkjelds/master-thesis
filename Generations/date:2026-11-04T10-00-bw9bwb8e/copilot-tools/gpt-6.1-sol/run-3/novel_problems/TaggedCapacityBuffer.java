@TaggedCapacityBuffer.ThreadSafe
public final class TaggedCapacityBuffer {
    @interface ThreadSafe {}

    private static final Object ID_MONITOR = new Object();
    private static final Object TRANSFER_MONITOR = new Object();
    private static long nextId = 1L;

    private final Object monitor = new Object();
    private final int capacity;
    private int occupancy;
    private int size;
    private boolean closed;
    private Node first;
    private Node last;

    public TaggedCapacityBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
    }

    /**
     * Inserts without waiting for capacity, evicting messages when necessary.
     * Tags are compared using exact, case-sensitive String equality.
     */
    public long offer(byte[] payload, String[] tags, String[] evictionTags) {
        byte[] payloadCopy = copyPayload(payload);
        String[] tagsCopy = copyTags(tags);
        String[] evictionTagsCopy = copyTags(evictionTags);

        synchronized (monitor) {
            ensureOpen();
            Node incoming = new Node(
                    new Message(allocateId(), payloadCopy, tagsCopy));

            Node candidate = first;
            while (!fits(payloadCopy.length) && candidate != null) {
                Node next = candidate.next;
                if (sharesTag(candidate.message.tags, evictionTagsCopy)) {
                    remove(candidate);
                }
                candidate = next;
            }

            while (!fits(payloadCopy.length)) {
                remove(first);
            }

            append(incoming);
            monitor.notifyAll();
            return incoming.message.id;
        }
    }

    /**
     * Waits interruptibly for actual free capacity; never evicts.
     */
    public long putBlocking(byte[] payload, String[] tags)
            throws InterruptedException {
        byte[] payloadCopy = copyPayload(payload);
        String[] tagsCopy = copyTags(tags);

        synchronized (monitor) {
            ensureOpen();
            while (!fits(payloadCopy.length)) {
                monitor.wait();
                ensureOpen();
            }

            Node incoming = new Node(
                    new Message(allocateId(), payloadCopy, tagsCopy));
            append(incoming);
            monitor.notifyAll();
            return incoming.message.id;
        }
    }

    /**
     * Returns the oldest matching message, waiting interruptibly if necessary.
     * Returns null when closed and no matching message remains.
     */
    public Message takeMatching(String tag) throws InterruptedException {
        requireTag(tag);

        synchronized (monitor) {
            while (true) {
                Node matching = findMatching(tag);
                if (matching != null) {
                    Message result = detachedCopy(matching.message);
                    remove(matching);
                    monitor.notifyAll();
                    return result;
                }
                if (closed) {
                    return null;
                }
                monitor.wait();
            }
        }
    }

    /**
     * Atomically appends the oldest matching message to the destination.
     * Does not wait for capacity and never evicts.
     */
    public boolean moveTo(TaggedCapacityBuffer destination, String tag) {
        if (destination == null) {
            throw new NullPointerException("destination");
        }
        if (destination == this) {
            throw new IllegalArgumentException("Cannot move to the same buffer");
        }
        requireTag(tag);

        // Only transfers acquire two buffer monitors. Serializing their
        // acquisition prevents opposite-direction transfers from deadlocking.
        synchronized (TRANSFER_MONITOR) {
            synchronized (monitor) {
                synchronized (destination.monitor) {
                    if (closed || destination.closed) {
                        return false;
                    }

                    Node matching = findMatching(tag);
                    if (matching == null
                            || !destination.fits(matching.message.payload.length)) {
                        return false;
                    }

                    remove(matching);
                    destination.append(matching);
                    monitor.notifyAll();
                    destination.monitor.notifyAll();
                    return true;
                }
            }
        }
    }

    /**
     * Rejects subsequent insertions and transfers, but permits existing
     * messages to drain.
     */
    public void close() {
        synchronized (monitor) {
            closed = true;
            monitor.notifyAll();
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            Message[] messages = new Message[size];
            Node current = first;
            int index = 0;
            while (current != null) {
                messages[index++] = detachedCopy(current.message);
                current = current.next;
            }
            return new Snapshot(messages, occupancy, capacity, closed);
        }
    }

    private byte[] copyPayload(byte[] payload) {
        if (payload == null) {
            throw new NullPointerException("payload");
        }
        if (payload.length == 0) {
            throw new IllegalArgumentException("Payload must not be empty");
        }
        if (payload.length > capacity) {
            throw new IllegalArgumentException("Payload exceeds capacity");
        }
        return payload.clone();
    }

    private static String[] copyTags(String[] tags) {
        if (tags == null) {
            throw new NullPointerException("tags");
        }

        String[] copy = tags.clone();
        for (int i = 0; i < copy.length; i++) {
            requireTag(copy[i]);
        }
        return copy;
    }

    private static void requireTag(String tag) {
        if (tag == null) {
            throw new NullPointerException("tag");
        }
    }

    private static long allocateId() {
        synchronized (ID_MONITOR) {
            if (nextId == 0L) {
                throw new IllegalStateException("Message IDs exhausted");
            }
            long id = nextId;
            nextId = id == 0x7fffffffffffffffL ? 0L : id + 1L;
            return id;
        }
    }

    private static Message detachedCopy(Message message) {
        return new Message(
                message.id, message.payload.clone(), message.tags.clone());
    }

    private static boolean hasTag(String[] tags, String tag) {
        for (int i = 0; i < tags.length; i++) {
            if (tags[i].equals(tag)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sharesTag(String[] tags, String[] otherTags) {
        for (int i = 0; i < otherTags.length; i++) {
            if (hasTag(tags, otherTags[i])) {
                return true;
            }
        }
        return false;
    }

    // The following instance helpers require this buffer's monitor.
    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Buffer is closed");
        }
    }

    private boolean fits(int length) {
        return length <= capacity - occupancy;
    }

    private Node findMatching(String tag) {
        Node current = first;
        while (current != null) {
            if (hasTag(current.message.tags, tag)) {
                return current;
            }
            current = current.next;
        }
        return null;
    }

    private void append(Node node) {
        node.previous = last;
        node.next = null;
        if (last == null) {
            first = node;
        } else {
            last.next = node;
        }
        last = node;
        occupancy += node.message.payload.length;
        size++;
    }

    private void remove(Node node) {
        if (node.previous == null) {
            first = node.next;
        } else {
            node.previous.next = node.next;
        }

        if (node.next == null) {
            last = node.previous;
        } else {
            node.next.previous = node.previous;
        }

        node.previous = null;
        node.next = null;
        occupancy -= node.message.payload.length;
        size--;
    }

    private static final class Node {
        private final Message message;
        private Node previous;
        private Node next;

        private Node(Message message) {
            this.message = message;
        }
    }

    /**
     * Immutable message; array accessors return independent copies.
     */
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

    /**
     * Immutable, coherent snapshot in FIFO order.
     */
    public static final class Snapshot {
        private final Message[] messages;
        private final int occupancy;
        private final int capacity;
        private final boolean closed;

        private Snapshot(
                Message[] messages, int occupancy, int capacity, boolean closed) {
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
}

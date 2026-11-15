@TaggedCapacityBuffer.ThreadSafe
public final class TaggedCapacityBuffer {
    @interface ThreadSafe {}

    private static final Object ID_LOCK = new Object();
    private static final Object TRANSFER_LOCK = new Object();
    private static long nextMessageId = 1;

    private final Object monitor = new Object();
    private final int capacity;
    private int occupancy;
    private int messageCount;
    private boolean closed;
    private Node head;
    private Node tail;

    public TaggedCapacityBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
    }

    /**
     * Inserts immediately, evicting preferred-tagged messages first, then
     * other oldest messages. Returns the new message's globally unique ID.
     */
    public long offer(byte[] payload, String[] tags, String[] evictionTags) {
        byte[] payloadCopy = validatedPayloadCopy(payload);
        String[] tagsCopy = copyTags(tags);
        String[] evictionTagsCopy = copyTags(evictionTags);

        synchronized (monitor) {
            ensureOpen();

            Message message =
                    new Message(allocateId(), payloadCopy, tagsCopy);
            Node inserted = new Node(message);

            Node candidate = head;
            while (payloadCopy.length > capacity - occupancy
                    && candidate != null) {
                Node next = candidate.next;
                if (sharesTag(candidate.message.tags, evictionTagsCopy)) {
                    unlink(candidate);
                }
                candidate = next;
            }

            while (payloadCopy.length > capacity - occupancy) {
                unlink(head);
            }

            append(inserted);
            monitor.notifyAll();
            return message.id;
        }
    }

    /**
     * Waits interruptibly for capacity without evicting any message.
     */
    public long putBlocking(byte[] payload, String[] tags)
            throws InterruptedException {
        byte[] payloadCopy = validatedPayloadCopy(payload);
        String[] tagsCopy = copyTags(tags);

        synchronized (monitor) {
            while (!closed && payloadCopy.length > capacity - occupancy) {
                monitor.wait();
            }
            ensureOpen();

            Message message =
                    new Message(allocateId(), payloadCopy, tagsCopy);
            append(new Node(message));
            monitor.notifyAll();
            return message.id;
        }
    }

    /**
     * Waits interruptibly for the oldest exact-tag match. Returns null when
     * closed and no matching message remains.
     */
    public Message takeMatching(String tag) throws InterruptedException {
        requireTag(tag);

        synchronized (monitor) {
            Node match = findMatching(tag);
            while (match == null && !closed) {
                monitor.wait();
                match = findMatching(tag);
            }

            if (match == null) {
                return null;
            }

            Message result = match.message.detachedCopy();
            unlink(match);
            monitor.notifyAll();
            return result;
        }
    }

    /**
     * Atomically transfers the oldest exact-tag match without eviction or
     * waiting for capacity. A successful transfer appends at the destination.
     */
    public boolean moveTo(TaggedCapacityBuffer destination, String tag) {
        if (destination == null) {
            throw new NullPointerException("destination");
        }
        if (destination == this) {
            throw new IllegalArgumentException("Cannot transfer to self");
        }
        requireTag(tag);

        // Only transfers acquire this gate, always before either buffer lock.
        synchronized (TRANSFER_LOCK) {
            synchronized (monitor) {
                synchronized (destination.monitor) {
                    if (closed || destination.closed) {
                        return false;
                    }

                    Node match = findMatching(tag);
                    if (match == null
                            || match.message.payload.length
                            > destination.capacity - destination.occupancy) {
                        return false;
                    }

                    unlink(match);
                    destination.append(match);
                    monitor.notifyAll();
                    destination.monitor.notifyAll();
                    return true;
                }
            }
        }
    }

    public void close() {
        synchronized (monitor) {
            closed = true;
            monitor.notifyAll();
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            Message[] messages = new Message[messageCount];
            Node current = head;
            for (int i = 0; i < messages.length; i++) {
                messages[i] = current.message.detachedCopy();
                current = current.next;
            }
            return new Snapshot(messages, occupancy, capacity, closed);
        }
    }

    private byte[] validatedPayloadCopy(byte[] payload) {
        if (payload == null) {
            throw new NullPointerException("payload");
        }
        if (payload.length == 0 || payload.length > capacity) {
            throw new IllegalArgumentException(
                    "Payload length must be between 1 and capacity");
        }
        return copyBytes(payload);
    }

    private static byte[] copyBytes(byte[] source) {
        byte[] copy = new byte[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }

    private static String[] copyTags(String[] source) {
        if (source == null) {
            throw new NullPointerException("tags");
        }

        String[] copy = new String[source.length];
        for (int i = 0; i < source.length; i++) {
            String tag = source[i];
            requireTag(tag);
            copy[i] = tag;
        }
        return copy;
    }

    private static void requireTag(String tag) {
        if (tag == null) {
            throw new NullPointerException("tag");
        }
    }

    private static long allocateId() {
        synchronized (ID_LOCK) {
            if (nextMessageId == 0) {
                throw new IllegalStateException("Message IDs exhausted");
            }
            long id = nextMessageId;
            nextMessageId = id == Long.MAX_VALUE ? 0 : id + 1;
            return id;
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Buffer is closed");
        }
    }

    private static boolean containsTag(String[] tags, String tag) {
        for (int i = 0; i < tags.length; i++) {
            if (tags[i].equals(tag)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sharesTag(String[] tags, String[] otherTags) {
        for (int i = 0; i < otherTags.length; i++) {
            if (containsTag(tags, otherTags[i])) {
                return true;
            }
        }
        return false;
    }

    // List and accounting helpers require the owning buffer's monitor.
    private Node findMatching(String tag) {
        Node current = head;
        while (current != null) {
            if (containsTag(current.message.tags, tag)) {
                return current;
            }
            current = current.next;
        }
        return null;
    }

    private void append(Node node) {
        node.previous = tail;
        node.next = null;
        if (tail == null) {
            head = node;
        } else {
            tail.next = node;
        }
        tail = node;
        occupancy += node.message.payload.length;
        messageCount++;
    }

    private void unlink(Node node) {
        if (node.previous == null) {
            head = node.next;
        } else {
            node.previous.next = node.next;
        }
        if (node.next == null) {
            tail = node.previous;
        } else {
            node.next.previous = node.previous;
        }

        occupancy -= node.message.payload.length;
        messageCount--;
        node.previous = null;
        node.next = null;
    }

    private static final class Node {
        private final Message message;
        private Node previous;
        private Node next;

        private Node(Message message) {
            this.message = message;
        }
    }

    public static final class Message {
        private final long id;
        private final byte[] payload;
        private final String[] tags;

        private Message(long id, byte[] ownedPayload, String[] ownedTags) {
            this.id = id;
            this.payload = ownedPayload;
            this.tags = ownedTags;
        }

        public long getId() {
            return id;
        }

        public byte[] getPayload() {
            return copyBytes(payload);
        }

        public String[] getTags() {
            return copyTags(tags);
        }

        private Message detachedCopy() {
            return new Message(id, copyBytes(payload), copyTags(tags));
        }
    }

    public static final class Snapshot {
        private final Message[] messages;
        private final int occupancy;
        private final int capacity;
        private final boolean closed;

        private Snapshot(Message[] ownedMessages, int occupancy,
                         int capacity, boolean closed) {
            this.messages = ownedMessages;
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

@TaggedCapacityBuffer.ThreadSafe
public final class TaggedCapacityBuffer {
    @interface ThreadSafe {}

    private static final Object ID_LOCK = new Object();
    private static final Object TRANSFER_LOCK = new Object();
    private static long lastId;

    private final Object monitor = new Object();
    private final int capacity;
    private Node head;
    private Node tail;
    private int occupancy;
    private int count;
    private boolean closed;

    public TaggedCapacityBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
    }

    /**
     * Inserts without waiting for capacity, evicting preferred messages first.
     */
    public long offer(byte[] payload, String[] tags, String[] evictionTags) {
        byte[] payloadCopy = checkedPayloadCopy(payload);
        String[] tagsCopy = checkedTagsCopy(tags);
        String[] evictionTagsCopy = checkedTagsCopy(evictionTags);

        synchronized (monitor) {
            requireOpen();
            long id = allocateId();
            Node incoming = new Node(new Message(id, payloadCopy, tagsCopy));

            Node current = head;
            while (payloadCopy.length > capacity - occupancy
                    && current != null) {
                Node next = current.next;
                if (sharesAnyTag(current.message.tags, evictionTagsCopy)) {
                    unlink(current);
                }
                current = next;
            }

            while (payloadCopy.length > capacity - occupancy) {
                unlink(head);
            }

            append(incoming);
            monitor.notifyAll();
            return id;
        }
    }

    /**
     * Waits interruptibly for actual free capacity; never evicts.
     */
    public long putBlocking(byte[] payload, String[] tags)
            throws InterruptedException {
        byte[] payloadCopy = checkedPayloadCopy(payload);
        String[] tagsCopy = checkedTagsCopy(tags);

        synchronized (monitor) {
            while (!closed && payloadCopy.length > capacity - occupancy) {
                monitor.wait();
            }
            requireOpen();

            long id = allocateId();
            Node incoming = new Node(new Message(id, payloadCopy, tagsCopy));
            append(incoming);
            monitor.notifyAll();
            return id;
        }
    }

    /**
     * Waits for the oldest exact-tag match, or returns null after closure
     * when no matching message remains.
     */
    public Message takeMatching(String tag) throws InterruptedException {
        requireTag(tag);

        synchronized (monitor) {
            while (true) {
                Node matching = findMatching(tag);
                if (matching != null) {
                    Message result = detachedCopy(matching.message);
                    unlink(matching);
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
     * Atomically transfers the oldest match without eviction or capacity waits.
     */
    public boolean moveTo(TaggedCapacityBuffer destination, String tag) {
        if (destination == null) {
            throw new NullPointerException("destination");
        }
        if (destination == this) {
            throw new IllegalArgumentException("Cannot move to the same buffer");
        }
        requireTag(tag);

        // Only transfers acquire two buffer monitors; this gate orders them.
        synchronized (TRANSFER_LOCK) {
            synchronized (monitor) {
                synchronized (destination.monitor) {
                    if (closed || destination.closed) {
                        return false;
                    }

                    Node matching = findMatching(tag);
                    if (matching == null
                            || matching.message.payload.length
                            > destination.capacity - destination.occupancy) {
                        return false;
                    }

                    unlink(matching);
                    destination.append(matching);
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
            Message[] messages = new Message[count];
            int index = 0;
            for (Node node = head; node != null; node = node.next) {
                messages[index++] = detachedCopy(node.message);
            }
            return new Snapshot(messages, occupancy, capacity, closed);
        }
    }

    private byte[] checkedPayloadCopy(byte[] payload) {
        if (payload == null) {
            throw new NullPointerException("payload");
        }
        if (payload.length == 0 || payload.length > capacity) {
            throw new IllegalArgumentException(
                    "Payload length must be between 1 and capacity");
        }
        return copyBytes(payload);
    }

    private static String[] checkedTagsCopy(String[] tags) {
        if (tags == null) {
            throw new NullPointerException("tags");
        }

        String[] copy = new String[tags.length];
        for (int i = 0; i < tags.length; i++) {
            String tag = tags[i];
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

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Buffer is closed");
        }
    }

    private static long allocateId() {
        synchronized (ID_LOCK) {
            if (lastId == 9223372036854775807L) {
                throw new IllegalStateException("Message IDs exhausted");
            }
            return ++lastId;
        }
    }

    // List and accounting helpers require the owning buffer's monitor.
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
        count++;
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
        count--;
        node.previous = null;
        node.next = null;
    }

    private Node findMatching(String tag) {
        for (Node node = head; node != null; node = node.next) {
            for (int i = 0; i < node.message.tags.length; i++) {
                if (tag.equals(node.message.tags[i])) {
                    return node;
                }
            }
        }
        return null;
    }

    private static boolean sharesAnyTag(String[] tags, String[] candidates) {
        for (int i = 0; i < tags.length; i++) {
            for (int j = 0; j < candidates.length; j++) {
                if (tags[i].equals(candidates[j])) {
                    return true;
                }
            }
        }
        return false;
    }

    private static byte[] copyBytes(byte[] source) {
        byte[] copy = new byte[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }

    private static String[] copyTags(String[] source) {
        String[] copy = new String[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }

    private static Message detachedCopy(Message message) {
        return new Message(
                message.id, copyBytes(message.payload), copyTags(message.tags));
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
     * Immutable message; array accessors return defensive copies.
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
            return copyBytes(payload);
        }

        public String[] getTags() {
            return copyTags(tags);
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

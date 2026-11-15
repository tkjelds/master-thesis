@TaggedCapacityBuffer.ThreadSafe
public final class TaggedCapacityBuffer {
    @interface ThreadSafe {}

    private static final Object ID_LOCK = new Object();
    private static final Object TRANSFER_LOCK = new Object();
    private static long nextId = 1;

    private final Object monitor = new Object();
    private final int capacity;
    private int occupancy;
    private boolean closed;
    private Node head;
    private Node tail;

    private static final class Node {
        final long id;
        final byte[] payload;
        final String[] tags;
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

        private Message(Node node) {
            this.id = node.id;
            this.payload = node.payload.clone();
            this.tags = node.tags.clone();
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

    public TaggedCapacityBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    public long offer(byte[] payload, String[] tags, String[] evictionTags) {
        byte[] payloadCopy = copyPayload(payload);
        String[] tagsCopy = copyTags(tags);
        String[] evictionTagsCopy = copyTags(evictionTags);

        synchronized (monitor) {
            ensureOpen();
            Node inserted = new Node(allocateId(), payloadCopy, tagsCopy);

            Node previous = null;
            Node current = head;
            while (current != null && !fits(payloadCopy.length)) {
                Node next = current.next;
                if (sharesAnyTag(current.tags, evictionTagsCopy)) {
                    unlink(previous, current);
                } else {
                    previous = current;
                }
                current = next;
            }

            while (!fits(payloadCopy.length)) {
                unlink(null, head);
            }

            append(inserted);
            monitor.notifyAll();
            return inserted.id;
        }
    }

    public long putBlocking(byte[] payload, String[] tags) throws InterruptedException {
        byte[] payloadCopy = copyPayload(payload);
        String[] tagsCopy = copyTags(tags);

        synchronized (monitor) {
            while (true) {
                ensureOpen();
                if (fits(payloadCopy.length)) {
                    break;
                }
                monitor.wait();
            }

            Node inserted = new Node(allocateId(), payloadCopy, tagsCopy);
            append(inserted);
            monitor.notifyAll();
            return inserted.id;
        }
    }

    public Message takeMatching(String tag) throws InterruptedException {
        requireTag(tag);

        synchronized (monitor) {
            while (true) {
                Node previous = null;
                Node current = head;
                while (current != null && !hasTag(current.tags, tag)) {
                    previous = current;
                    current = current.next;
                }

                if (current != null) {
                    Message result = new Message(current);
                    unlink(previous, current);
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

    public boolean moveTo(TaggedCapacityBuffer destination, String tag) {
        if (destination == null) {
            throw new NullPointerException("destination");
        }
        requireTag(tag);
        if (destination == this) {
            throw new IllegalArgumentException("destination must be a different buffer");
        }

        synchronized (TRANSFER_LOCK) {
            synchronized (monitor) {
                synchronized (destination.monitor) {
                    if (closed || destination.closed) {
                        return false;
                    }

                    Node previous = null;
                    Node current = head;
                    while (current != null && !hasTag(current.tags, tag)) {
                        previous = current;
                        current = current.next;
                    }

                    if (current == null || !destination.fits(current.payload.length)) {
                        return false;
                    }

                    unlink(previous, current);
                    destination.append(current);
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
            int count = 0;
            for (Node current = head; current != null; current = current.next) {
                count++;
            }

            Message[] messages = new Message[count];
            int index = 0;
            for (Node current = head; current != null; current = current.next) {
                messages[index++] = new Message(current);
            }
            return new Snapshot(messages, occupancy, capacity, closed);
        }
    }

    private byte[] copyPayload(byte[] payload) {
        if (payload == null) {
            throw new NullPointerException("payload");
        }
        if (payload.length == 0 || payload.length > capacity) {
            throw new IllegalArgumentException("invalid payload length");
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
        synchronized (ID_LOCK) {
            if (nextId == 0) {
                throw new IllegalStateException("message IDs exhausted");
            }
            long id = nextId;
            nextId = id == 0x7fffffffffffffffL ? 0 : id + 1;
            return id;
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("buffer is closed");
        }
    }

    private boolean fits(int length) {
        return length <= capacity - occupancy;
    }

    private void append(Node node) {
        node.next = null;
        if (tail == null) {
            head = node;
        } else {
            tail.next = node;
        }
        tail = node;
        occupancy += node.payload.length;
    }

    private void unlink(Node previous, Node node) {
        if (previous == null) {
            head = node.next;
        } else {
            previous.next = node.next;
        }
        if (tail == node) {
            tail = previous;
        }
        occupancy -= node.payload.length;
        node.next = null;
    }

    private static boolean hasTag(String[] tags, String tag) {
        for (int i = 0; i < tags.length; i++) {
            if (tags[i].equals(tag)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sharesAnyTag(String[] tags, String[] otherTags) {
        for (int i = 0; i < otherTags.length; i++) {
            if (hasTag(tags, otherTags[i])) {
                return true;
            }
        }
        return false;
    }
}

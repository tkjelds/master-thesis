@TaggedCapacityBuffer.ThreadSafe
public final class TaggedCapacityBuffer {
    @interface ThreadSafe {}

    private static final Object ID_LOCK = new Object();
    private static final Object TRANSFER_LOCK = new Object();
    private static long nextId = 1L;

    private final Object lock = new Object();
    private final long capacity;
    private long occupancy;
    private boolean closed;
    private Node head;
    private Node tail;

    private static final class Node {
        private final Message message;
        private Node next;

        private Node(Message message) {
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
            return copyBytes(payload);
        }

        public String[] getTags() {
            return copyStrings(tags);
        }
    }

    public static final class Snapshot {
        private final Message[] messages;
        private final long occupancy;
        private final long capacity;
        private final boolean closed;

        private Snapshot(Message[] messages, long occupancy,
                         long capacity, boolean closed) {
            this.messages = messages;
            this.occupancy = occupancy;
            this.capacity = capacity;
            this.closed = closed;
        }

        public Message[] getMessages() {
            Message[] result = new Message[messages.length];
            for (int i = 0; i < messages.length; i++) {
                result[i] = messages[i];
            }
            return result;
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

    public TaggedCapacityBuffer(long capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
    }

    public long offer(byte[] payload, String[] tags, String[] evictionTags) {
        byte[] payloadCopy = validatedPayload(payload);
        String[] tagsCopy = validatedTags(tags);
        String[] evictionTagsCopy = validatedTags(evictionTags);

        synchronized (lock) {
            ensureOpen();

            long id = allocateId();
            Node newNode = new Node(new Message(id, payloadCopy, tagsCopy));

            Node previous = null;
            Node current = head;
            while (capacity - occupancy < payloadCopy.length && current != null) {
                Node next = current.next;
                if (sharesTag(current.message.tags, evictionTagsCopy)) {
                    unlink(previous, current);
                } else {
                    previous = current;
                }
                current = next;
            }

            while (capacity - occupancy < payloadCopy.length) {
                unlink(null, head);
            }

            append(newNode);
            lock.notifyAll();
            return id;
        }
    }

    public long putBlocking(byte[] payload, String[] tags)
            throws InterruptedException {
        byte[] payloadCopy = validatedPayload(payload);
        String[] tagsCopy = validatedTags(tags);

        synchronized (lock) {
            while (!closed && capacity - occupancy < payloadCopy.length) {
                lock.wait();
            }
            ensureOpen();

            long id = allocateId();
            Node node = new Node(new Message(id, payloadCopy, tagsCopy));
            append(node);
            lock.notifyAll();
            return id;
        }
    }

    public Message takeMatching(String tag) throws InterruptedException {
        if (tag == null) {
            throw new IllegalArgumentException("Tag must not be null");
        }

        synchronized (lock) {
            while (true) {
                Node previous = null;
                Node current = head;
                while (current != null && !hasTag(current.message.tags, tag)) {
                    previous = current;
                    current = current.next;
                }

                if (current != null) {
                    Message result = detachedCopy(current.message);
                    unlink(previous, current);
                    lock.notifyAll();
                    return result;
                }

                if (closed) {
                    return null;
                }
                lock.wait();
            }
        }
    }

    public boolean moveTo(TaggedCapacityBuffer destination, String tag) {
        if (destination == null || tag == null) {
            throw new IllegalArgumentException("Destination and tag must not be null");
        }
        if (destination == this) {
            throw new IllegalArgumentException("Destination must be different");
        }

        synchronized (TRANSFER_LOCK) {
            synchronized (lock) {
                synchronized (destination.lock) {
                    if (closed || destination.closed) {
                        return false;
                    }

                    Node previous = null;
                    Node current = head;
                    while (current != null && !hasTag(current.message.tags, tag)) {
                        previous = current;
                        current = current.next;
                    }

                    if (current == null ||
                            destination.capacity - destination.occupancy
                                    < current.message.payload.length) {
                        return false;
                    }

                    unlink(previous, current);
                    destination.append(current);
                    lock.notifyAll();
                    destination.lock.notifyAll();
                    return true;
                }
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
            int count = 0;
            for (Node current = head; current != null; current = current.next) {
                count++;
            }

            Message[] messages = new Message[count];
            int index = 0;
            for (Node current = head; current != null; current = current.next) {
                messages[index++] = detachedCopy(current.message);
            }
            return new Snapshot(messages, occupancy, capacity, closed);
        }
    }

    private byte[] validatedPayload(byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > capacity) {
            throw new IllegalArgumentException("Invalid payload");
        }
        return copyBytes(payload);
    }

    private static String[] validatedTags(String[] tags) {
        if (tags == null) {
            throw new IllegalArgumentException("Tags must not be null");
        }
        String[] copy = new String[tags.length];
        for (int i = 0; i < tags.length; i++) {
            String tag = tags[i];
            if (tag == null) {
                throw new IllegalArgumentException("Tags must not contain null");
            }
            copy[i] = tag;
        }
        return copy;
    }

    private static byte[] copyBytes(byte[] source) {
        byte[] copy = new byte[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }

    private static String[] copyStrings(String[] source) {
        String[] copy = new String[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }

    private static Message detachedCopy(Message message) {
        return new Message(message.id, copyBytes(message.payload),
                copyStrings(message.tags));
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

    private static long allocateId() {
        synchronized (ID_LOCK) {
            if (nextId == 0) {
                throw new IllegalStateException("Message IDs exhausted");
            }
            long id = nextId;
            if (nextId == 9223372036854775807L) {
                nextId = 0;
            } else {
                nextId++;
            }
            return id;
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Buffer is closed");
        }
    }

    private void append(Node node) {
        node.next = null;
        if (tail == null) {
            head = node;
        } else {
            tail.next = node;
        }
        tail = node;
        occupancy += node.message.payload.length;
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
        occupancy -= node.message.payload.length;
        node.next = null;
    }
}

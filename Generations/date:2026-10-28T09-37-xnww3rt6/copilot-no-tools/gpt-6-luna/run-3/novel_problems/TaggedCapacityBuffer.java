@TaggedCapacityBuffer.ThreadSafe
public final class TaggedCapacityBuffer {
    @interface ThreadSafe {}

    private static final Object GLOBAL_LOCK = new Object();
    private static long nextMessageId = 1;
    private static long nextLockOrder = 1;
    private static boolean messageIdsExhausted;
    private static boolean lockOrdersExhausted;

    private final Object monitor = new Object();
    private final int capacity;
    private final long lockOrder;

    private Node head;
    private Node tail;
    private int occupancy;
    private boolean closed;

    public TaggedCapacityBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }

        this.capacity = capacity;
        synchronized (GLOBAL_LOCK) {
            if (lockOrdersExhausted) {
                throw new IllegalStateException("Buffer lock order exhausted");
            }
            this.lockOrder = nextLockOrder;
            if (nextLockOrder == Long.MAX_VALUE) {
                lockOrdersExhausted = true;
            } else {
                nextLockOrder++;
            }
        }
    }

    public long offer(byte[] payload, String[] tags, String[] evictionTags) {
        byte[] payloadCopy = copyAndValidatePayload(payload);
        String[] tagsCopy = copyAndValidateTags(tags, "tags");
        String[] evictionTagsCopy = copyAndValidateTags(evictionTags, "evictionTags");

        if (payloadCopy.length > capacity) {
            throw new IllegalArgumentException("Payload exceeds buffer capacity");
        }

        synchronized (monitor) {
            ensureOpen();

            long id = allocateMessageId();
            int requiredOccupancy = occupancy + payloadCopy.length;

            Node current = head;
            while (requiredOccupancy > capacity && current != null) {
                Node next = current.next;
                if (sharesAnyTag(current.message.tags, evictionTagsCopy)) {
                    unlink(current);
                    occupancy -= current.message.payload.length;
                    requiredOccupancy -= current.message.payload.length;
                }
                current = next;
            }

            while (requiredOccupancy > capacity) {
                Node oldest = head;
                unlink(oldest);
                occupancy -= oldest.message.payload.length;
                requiredOccupancy -= oldest.message.payload.length;
            }

            append(new Message(id, payloadCopy, tagsCopy));
            occupancy += payloadCopy.length;
            monitor.notifyAll();
            return id;
        }
    }

    public long putBlocking(byte[] payload, String[] tags) throws InterruptedException {
        byte[] payloadCopy = copyAndValidatePayload(payload);
        String[] tagsCopy = copyAndValidateTags(tags, "tags");

        if (payloadCopy.length > capacity) {
            throw new IllegalArgumentException("Payload exceeds buffer capacity");
        }

        synchronized (monitor) {
            while (!closed && occupancy + payloadCopy.length > capacity) {
                monitor.wait();
            }
            ensureOpen();

            long id = allocateMessageId();
            append(new Message(id, payloadCopy, tagsCopy));
            occupancy += payloadCopy.length;
            monitor.notifyAll();
            return id;
        }
    }

    public Message takeMatching(String tag) throws InterruptedException {
        if (tag == null) {
            throw new NullPointerException("tag");
        }

        synchronized (monitor) {
            while (true) {
                Node match = findMatching(tag);
                if (match != null) {
                    Message result = new Message(
                            match.message.id,
                            match.message.payload,
                            match.message.tags);
                    unlink(match);
                    occupancy -= match.message.payload.length;
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
        if (destination == this) {
            throw new IllegalArgumentException("Destination must differ from source");
        }
        if (tag == null) {
            throw new NullPointerException("tag");
        }

        TaggedCapacityBuffer first = lockOrder < destination.lockOrder ? this : destination;
        TaggedCapacityBuffer second = first == this ? destination : this;

        synchronized (first.monitor) {
            synchronized (second.monitor) {
                if (closed || destination.closed) {
                    return false;
                }

                Node match = findMatching(tag);
                if (match == null
                        || destination.occupancy + match.message.payload.length
                                > destination.capacity) {
                    return false;
                }

                unlink(match);
                occupancy -= match.message.payload.length;
                destination.append(match.message);
                destination.occupancy += match.message.payload.length;

                monitor.notifyAll();
                destination.monitor.notifyAll();
                return true;
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
            for (Node node = head; node != null; node = node.next) {
                count++;
            }

            Message[] messages = new Message[count];
            int index = 0;
            for (Node node = head; node != null; node = node.next) {
                messages[index++] = new Message(
                        node.message.id,
                        node.message.payload,
                        node.message.tags);
            }

            return new Snapshot(messages, occupancy, capacity, closed);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Buffer is closed");
        }
    }

    private Node findMatching(String tag) {
        for (Node node = head; node != null; node = node.next) {
            if (containsTag(node.message.tags, tag)) {
                return node;
            }
        }
        return null;
    }

    private static boolean containsTag(String[] tags, String sought) {
        for (String tag : tags) {
            if (tag.equals(sought)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sharesAnyTag(String[] messageTags, String[] soughtTags) {
        for (String tag : messageTags) {
            for (String sought : soughtTags) {
                if (tag.equals(sought)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void append(Message message) {
        Node node = new Node(message);
        if (tail == null) {
            head = node;
            tail = node;
        } else {
            tail.next = node;
            node.previous = tail;
            tail = node;
        }
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

        node.previous = null;
        node.next = null;
    }

    private long allocateMessageId() {
        synchronized (GLOBAL_LOCK) {
            if (messageIdsExhausted) {
                throw new IllegalStateException("Message IDs exhausted");
            }
            long id = nextMessageId;
            if (nextMessageId == Long.MAX_VALUE) {
                messageIdsExhausted = true;
            } else {
                nextMessageId++;
            }
            return id;
        }
    }

    private static byte[] copyAndValidatePayload(byte[] payload) {
        if (payload == null) {
            throw new NullPointerException("payload");
        }
        if (payload.length == 0) {
            throw new IllegalArgumentException("Payload must not be empty");
        }
        return payload.clone();
    }

    private static String[] copyAndValidateTags(String[] tags, String name) {
        if (tags == null) {
            throw new NullPointerException(name);
        }
        String[] copy = tags.clone();
        for (String tag : copy) {
            if (tag == null) {
                throw new NullPointerException(name + " contains null");
            }
        }
        return copy;
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

        private Message(long id, byte[] payload, String[] tags) {
            this.id = id;
            this.payload = payload.clone();
            this.tags = tags.clone();
        }

        public long id() {
            return id;
        }

        public byte[] payload() {
            return payload.clone();
        }

        public String[] tags() {
            return tags.clone();
        }
    }

    public static final class Snapshot {
        private final Message[] messages;
        private final int occupancy;
        private final int capacity;
        private final boolean closed;

        private Snapshot(Message[] messages, int occupancy, int capacity, boolean closed) {
            this.messages = messages.clone();
            this.occupancy = occupancy;
            this.capacity = capacity;
            this.closed = closed;
        }

        public Message[] messages() {
            return messages.clone();
        }

        public int occupancy() {
            return occupancy;
        }

        public int capacity() {
            return capacity;
        }

        public boolean closed() {
            return closed;
        }
    }
}

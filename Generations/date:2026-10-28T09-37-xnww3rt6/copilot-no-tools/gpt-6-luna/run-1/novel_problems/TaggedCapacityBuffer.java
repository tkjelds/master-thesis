@TaggedCapacityBuffer.ThreadSafe
public final class TaggedCapacityBuffer {
    @interface ThreadSafe {}

    private static final Object ID_LOCK = new Object();
    private static long nextMessageId = 1;
    private static boolean messageIdsExhausted;
    private static long nextBufferOrder = 1;
    private static boolean bufferOrdersExhausted;

    private final int capacity;
    private final long lockOrder;
    private Node head;
    private Node tail;
    private int occupancy;
    private boolean closed;

    public TaggedCapacityBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.lockOrder = allocateBufferOrder();
    }

    public long offer(byte[] payload, String[] tags, String[] evictionTags) {
        byte[] payloadCopy = copyAndValidatePayload(payload);
        String[] tagsCopy = copyAndValidateTags(tags);
        String[] evictionTagsCopy = copyAndValidateTags(evictionTags);

        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("buffer is closed");
            }

            long id = allocateMessageId();
            Message message = new Message(id, payloadCopy, tagsCopy);

            while ((long) occupancy + payloadCopy.length > capacity) {
                Node victim = findOldestWithAnyTag(evictionTagsCopy);
                if (victim == null) {
                    victim = head;
                }
                removeNode(victim);
            }

            append(new Node(message));
            notifyAll();
            return id;
        }
    }

    public long putBlocking(byte[] payload, String[] tags) throws InterruptedException {
        byte[] payloadCopy = copyAndValidatePayload(payload);
        String[] tagsCopy = copyAndValidateTags(tags);

        synchronized (this) {
            while (!closed && (long) occupancy + payloadCopy.length > capacity) {
                wait();
            }
            if (closed) {
                throw new IllegalStateException("buffer is closed");
            }

            long id = allocateMessageId();
            append(new Node(new Message(id, payloadCopy, tagsCopy)));
            notifyAll();
            return id;
        }
    }

    public Message takeMatching(String tag) throws InterruptedException {
        if (tag == null) {
            throw new IllegalArgumentException("tag must not be null");
        }

        synchronized (this) {
            Node matching;
            while ((matching = findOldestWithTag(tag)) == null && !closed) {
                wait();
            }
            if (matching == null) {
                return null;
            }

            removeNode(matching);
            notifyAll();
            return copyMessage(matching.message);
        }
    }

    public boolean moveTo(TaggedCapacityBuffer destination, String tag) {
        if (destination == null) {
            throw new IllegalArgumentException("destination must not be null");
        }
        if (destination == this) {
            throw new IllegalArgumentException("destination must be a different buffer");
        }
        if (tag == null) {
            throw new IllegalArgumentException("tag must not be null");
        }

        TaggedCapacityBuffer first = lockOrder < destination.lockOrder ? this : destination;
        TaggedCapacityBuffer second = first == this ? destination : this;

        synchronized (first) {
            synchronized (second) {
                if (closed || destination.closed) {
                    return false;
                }

                Node matching = findOldestWithTag(tag);
                if (matching == null
                        || (long) destination.occupancy + matching.message.payload.length
                                > destination.capacity) {
                    return false;
                }

                removeNode(matching);
                destination.append(matching);
                notifyAll();
                destination.notifyAll();
                return true;
            }
        }
    }

    public void close() {
        synchronized (this) {
            closed = true;
            notifyAll();
        }
    }

    public Snapshot snapshot() {
        synchronized (this) {
            Message[] messages = new Message[countNodes()];
            int index = 0;
            for (Node node = head; node != null; node = node.next) {
                messages[index++] = copyMessage(node.message);
            }
            return new Snapshot(messages, occupancy, capacity, closed);
        }
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
    }

    private void removeNode(Node node) {
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
        node.previous = null;
        node.next = null;
    }

    private Node findOldestWithTag(String tag) {
        for (Node node = head; node != null; node = node.next) {
            if (hasTag(node.message.tags, tag)) {
                return node;
            }
        }
        return null;
    }

    private Node findOldestWithAnyTag(String[] tags) {
        for (Node node = head; node != null; node = node.next) {
            for (int i = 0; i < tags.length; i++) {
                if (hasTag(node.message.tags, tags[i])) {
                    return node;
                }
            }
        }
        return null;
    }

    private int countNodes() {
        int count = 0;
        for (Node node = head; node != null; node = node.next) {
            count++;
        }
        return count;
    }

    private static boolean hasTag(String[] tags, String sought) {
        for (int i = 0; i < tags.length; i++) {
            if (tags[i].equals(sought)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] copyAndValidatePayload(byte[] payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        if (payload.length == 0) {
            throw new IllegalArgumentException("payload must not be empty");
        }
        return copyBytes(payload);
    }

    private static String[] copyAndValidateTags(String[] tags) {
        if (tags == null) {
            throw new IllegalArgumentException("tags must not be null");
        }
        String[] copy = new String[tags.length];
        for (int i = 0; i < tags.length; i++) {
            if (tags[i] == null) {
                throw new IllegalArgumentException("tag entries must not be null");
            }
            copy[i] = tags[i];
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

    private static Message copyMessage(Message message) {
        return new Message(message.id, message.payload, message.tags);
    }

    private static long allocateMessageId() {
        synchronized (ID_LOCK) {
            if (messageIdsExhausted) {
                throw new IllegalStateException("message ID space exhausted");
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

    private static long allocateBufferOrder() {
        synchronized (ID_LOCK) {
            if (bufferOrdersExhausted) {
                throw new IllegalStateException("buffer lock-order space exhausted");
            }
            long order = nextBufferOrder;
            if (nextBufferOrder == Long.MAX_VALUE) {
                bufferOrdersExhausted = true;
            } else {
                nextBufferOrder++;
            }
            return order;
        }
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
            this.payload = copyBytes(payload);
            this.tags = copyAndValidateTags(tags);
        }

        public long getId() {
            return id;
        }

        public byte[] getPayload() {
            return copyBytes(payload);
        }

        public String[] getTags() {
            return copyAndValidateTags(tags);
        }
    }

    public static final class Snapshot {
        private final Message[] messages;
        private final int occupancy;
        private final int capacity;
        private final boolean closed;

        private Snapshot(Message[] messages, int occupancy, int capacity, boolean closed) {
            this.messages = new Message[messages.length];
            for (int i = 0; i < messages.length; i++) {
                this.messages[i] = copyMessage(messages[i]);
            }
            this.occupancy = occupancy;
            this.capacity = capacity;
            this.closed = closed;
        }

        public Message[] getMessages() {
            Message[] copy = new Message[messages.length];
            for (int i = 0; i < messages.length; i++) {
                copy[i] = copyMessage(messages[i]);
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

@TaggedCapacityBuffer.ThreadSafe
public final class TaggedCapacityBuffer {
    @interface ThreadSafe {}

    private static final Object ID_LOCK = new Object();
    private static final Object MOVE_LOCK = new Object();

    private static long nextId = 1;
    private static boolean idsExhausted;

    private final int capacity;
    private int occupancy;
    private boolean closed;
    private Node head;
    private Node tail;

    public TaggedCapacityBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    public long offer(byte[] payload, String[] tags, String[] evictionTags) {
        validatePayload(payload);
        validateTags(tags, "tags");
        validateTags(evictionTags, "evictionTags");

        byte[] payloadCopy = payload.clone();
        String[] tagsCopy = tags.clone();
        String[] evictionTagsCopy = evictionTags.clone();

        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("buffer is closed");
            }

            Node previous = null;
            Node current = head;
            while (current != null && payloadCopy.length > capacity - occupancy) {
                Node next = current.next;
                if (sharesTag(current.message.tags, evictionTagsCopy)) {
                    removeNode(previous, current);
                } else {
                    previous = current;
                }
                current = next;
            }

            while (payloadCopy.length > capacity - occupancy) {
                removeNode(null, head);
            }

            long id = allocateId();
            append(new Node(new Message(id, payloadCopy, tagsCopy)));
            notifyAll();
            return id;
        }
    }

    public long putBlocking(byte[] payload, String[] tags) throws InterruptedException {
        validatePayload(payload);
        validateTags(tags, "tags");

        byte[] payloadCopy = payload.clone();
        String[] tagsCopy = tags.clone();

        synchronized (this) {
            while (!closed && payloadCopy.length > capacity - occupancy) {
                wait();
            }
            if (closed) {
                throw new IllegalStateException("buffer is closed");
            }

            long id = allocateId();
            append(new Node(new Message(id, payloadCopy, tagsCopy)));
            notifyAll();
            return id;
        }
    }

    public Message takeMatching(String tag) throws InterruptedException {
        if (tag == null) {
            throw new NullPointerException("tag");
        }

        synchronized (this) {
            Node previous;
            Node current;
            while (true) {
                previous = null;
                current = head;
                while (current != null && !hasTag(current.message.tags, tag)) {
                    previous = current;
                    current = current.next;
                }

                if (current != null) {
                    break;
                }
                if (closed) {
                    return null;
                }
                wait();
            }

            removeNode(previous, current);
            notifyAll();
            return copyMessage(current.message);
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
            throw new IllegalArgumentException("destination must differ from source");
        }

        synchronized (MOVE_LOCK) {
            synchronized (this) {
                synchronized (destination) {
                    if (closed || destination.closed) {
                        return false;
                    }

                    Node previous = null;
                    Node current = head;
                    while (current != null && !hasTag(current.message.tags, tag)) {
                        previous = current;
                        current = current.next;
                    }

                    if (current == null
                            || current.message.payload.length > destination.capacity - destination.occupancy) {
                        return false;
                    }

                    removeNode(previous, current);
                    destination.append(current);
                    notifyAll();
                    destination.notifyAll();
                    return true;
                }
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
            int count = 0;
            for (Node current = head; current != null; current = current.next) {
                count++;
            }

            Message[] messages = new Message[count];
            int index = 0;
            for (Node current = head; current != null; current = current.next) {
                messages[index++] = copyMessage(current.message);
            }
            return new Snapshot(messages, occupancy, capacity, closed);
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

    private void removeNode(Node previous, Node current) {
        if (current == null) {
            return;
        }
        if (previous == null) {
            head = current.next;
        } else {
            previous.next = current.next;
        }
        if (tail == current) {
            tail = previous;
        }
        occupancy -= current.message.payload.length;
        current.next = null;
    }

    private static long allocateId() {
        synchronized (ID_LOCK) {
            if (idsExhausted) {
                throw new IllegalStateException("message ID space exhausted");
            }
            long id = nextId;
            if (nextId == Long.MAX_VALUE) {
                idsExhausted = true;
            } else {
                nextId++;
            }
            return id;
        }
    }

    private static void validatePayload(byte[] payload) {
        if (payload == null) {
            throw new NullPointerException("payload");
        }
        if (payload.length == 0) {
            throw new IllegalArgumentException("payload must not be empty");
        }
    }

    private static void validateTags(String[] tags, String name) {
        if (tags == null) {
            throw new NullPointerException(name);
        }
        for (int i = 0; i < tags.length; i++) {
            if (tags[i] == null) {
                throw new NullPointerException(name + "[" + i + "]");
            }
        }
    }

    private static boolean hasTag(String[] tags, String tag) {
        for (int i = 0; i < tags.length; i++) {
            if (tag.equals(tags[i])) {
                return true;
            }
        }
        return false;
    }

    private static boolean sharesTag(String[] messageTags, String[] requestedTags) {
        for (int i = 0; i < messageTags.length; i++) {
            if (hasTag(requestedTags, messageTags[i])) {
                return true;
            }
        }
        return false;
    }

    private static Message copyMessage(Message message) {
        return new Message(message.id, message.payload.clone(), message.tags.clone());
    }

    private static final class Node {
        private final Message message;
        private Node next;

        private Node(Message message) {
            this.message = message;
        }
    }

    public static final class Message {
        public final long id;
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
        public final Message[] messages;
        public final int occupancy;
        public final int capacity;
        public final boolean closed;

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
}

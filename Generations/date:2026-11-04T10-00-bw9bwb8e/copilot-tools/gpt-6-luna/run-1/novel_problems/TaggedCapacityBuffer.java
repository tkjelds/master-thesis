@TaggedCapacityBuffer.ThreadSafe
public final class TaggedCapacityBuffer {
    @interface ThreadSafe {}

    private static final Object TRANSFER_LOCK = new Object();
    private static final Object ID_LOCK = new Object();
    private static long nextId = 1L;
    private static boolean idsExhausted;

    private static final class Node {
        final Message message;
        Node next;

        Node(Message message) {
            this.message = message;
        }
    }

    public static final class Message {
        public final long id;
        public final byte[] payload;
        public final String[] tags;

        private Message(long id, byte[] payload, String[] tags) {
            this.id = id;
            this.payload = copyBytes(payload);
            this.tags = copyStrings(tags);
        }

        public byte[] getPayload() {
            return copyBytes(payload);
        }

        public String[] getTags() {
            return copyStrings(tags);
        }

        private Message detachedCopy() {
            return new Message(id, payload, tags);
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
            Message[] result = new Message[messages.length];
            for (int i = 0; i < messages.length; i++) {
                result[i] = messages[i].detachedCopy();
            }
            return result;
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
    private Node head;
    private Node tail;
    private int occupancy;
    private boolean closed;

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

        byte[] payloadCopy = copyBytes(payload);
        String[] tagsCopy = copyStrings(tags);
        String[] evictionTagsCopy = copyStrings(evictionTags);

        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("buffer is closed");
            }

            long id = allocateId();
            Message message = new Message(id, payloadCopy, tagsCopy);
            Node node = new Node(message);
            int neededOccupancy = capacity - payloadCopy.length;

            Node previous = null;
            Node current = head;
            while (occupancy > neededOccupancy && current != null) {
                Node next = current.next;
                if (sharesAnyTag(current.message.tags, evictionTagsCopy)) {
                    removeNode(previous, current);
                } else {
                    previous = current;
                }
                current = next;
            }

            while (occupancy > neededOccupancy) {
                removeNode(null, head);
            }

            append(node);
            notifyAll();
            return id;
        }
    }

    public long putBlocking(byte[] payload, String[] tags) throws InterruptedException {
        validatePayload(payload);
        validateTags(tags, "tags");

        byte[] payloadCopy = copyBytes(payload);
        String[] tagsCopy = copyStrings(tags);

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
            throw new IllegalArgumentException("tag must not be null");
        }

        synchronized (this) {
            while (true) {
                Node previous = null;
                Node current = head;
                while (current != null) {
                    if (hasTag(current.message.tags, tag)) {
                        Message result = current.message.detachedCopy();
                        removeNode(previous, current);
                        notifyAll();
                        return result;
                    }
                    previous = current;
                    current = current.next;
                }

                if (closed) {
                    return null;
                }
                wait();
            }
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

        synchronized (TRANSFER_LOCK) {
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
            if (!closed) {
                closed = true;
                notifyAll();
            }
        }
    }

    public Snapshot snapshot() {
        synchronized (this) {
            int count = 0;
            Node current = head;
            while (current != null) {
                count++;
                current = current.next;
            }

            Message[] messages = new Message[count];
            current = head;
            for (int i = 0; i < count; i++) {
                messages[i] = current.message.detachedCopy();
                current = current.next;
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

    private void removeNode(Node previous, Node node) {
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
            throw new IllegalArgumentException("payload must not be null");
        }
        if (payload.length == 0) {
            throw new IllegalArgumentException("payload must not be empty");
        }
    }

    private static void validateTags(String[] tags, String name) {
        if (tags == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        for (int i = 0; i < tags.length; i++) {
            if (tags[i] == null) {
                throw new IllegalArgumentException(name + " must not contain null");
            }
        }
    }

    private static byte[] copyBytes(byte[] source) {
        byte[] result = new byte[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i];
        }
        return result;
    }

    private static String[] copyStrings(String[] source) {
        String[] result = new String[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i];
        }
        return result;
    }

    private static boolean hasTag(String[] tags, String tag) {
        for (int i = 0; i < tags.length; i++) {
            if (tag.equals(tags[i])) {
                return true;
            }
        }
        return false;
    }

    private static boolean sharesAnyTag(String[] messageTags, String[] soughtTags) {
        for (int i = 0; i < messageTags.length; i++) {
            if (hasTag(soughtTags, messageTags[i])) {
                return true;
            }
        }
        return false;
    }
}

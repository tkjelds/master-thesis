@TaggedCapacityBuffer.ThreadSafe
public final class TaggedCapacityBuffer {
    @interface ThreadSafe {}

    private static final Object MESSAGE_ID_LOCK = new Object();
    private static long nextMessageId = 1;

    private static final Object BUFFER_ORDER_LOCK = new Object();
    private static long nextBufferOrder = 1;

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
        synchronized (BUFFER_ORDER_LOCK) {
            if (nextBufferOrder <= 0) {
                throw new IllegalStateException("buffer lock order exhausted");
            }
            this.lockOrder = nextBufferOrder++;
        }
    }

    public long offer(byte[] payload, String[] tags, String[] evictionTags) {
        byte[] payloadCopy = copyPayload(payload);
        String[] tagsCopy = copyTags(tags);
        String[] evictionTagsCopy = copyTags(evictionTags);
        if (payloadCopy.length > capacity) {
            throw new IllegalArgumentException("payload exceeds buffer capacity");
        }

        synchronized (this) {
            ensureOpen();

            long id = allocateMessageId();
            Message message = new Message(id, payloadCopy, tagsCopy);
            Node newNode = new Node(message);

            while (payloadCopy.length > capacity - occupancy) {
                Node previous = null;
                Node candidate = head;
                while (candidate != null && !hasAnyTag(candidate.message.tags, evictionTagsCopy)) {
                    previous = candidate;
                    candidate = candidate.next;
                }
                if (candidate == null) {
                    removeHead();
                } else {
                    removeNode(previous, candidate);
                }
            }

            append(newNode);
            notifyAll();
            return id;
        }
    }

    public long putBlocking(byte[] payload, String[] tags) throws InterruptedException {
        byte[] payloadCopy = copyPayload(payload);
        String[] tagsCopy = copyTags(tags);
        if (payloadCopy.length > capacity) {
            throw new IllegalArgumentException("payload exceeds buffer capacity");
        }

        synchronized (this) {
            while (!closed && payloadCopy.length > capacity - occupancy) {
                wait();
            }
            ensureOpen();

            long id = allocateMessageId();
            Message message = new Message(id, payloadCopy, tagsCopy);
            append(new Node(message));
            notifyAll();
            return id;
        }
    }

    public Message takeMatching(String tag) throws InterruptedException {
        requireTag(tag);

        synchronized (this) {
            while (true) {
                Node previous = null;
                Node candidate = head;
                while (candidate != null && !hasTag(candidate.message.tags, tag)) {
                    previous = candidate;
                    candidate = candidate.next;
                }

                if (candidate != null) {
                    Message detached = candidate.message.detachedCopy();
                    removeNode(previous, candidate);
                    notifyAll();
                    return detached;
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
        requireTag(tag);
        if (destination == this) {
            throw new IllegalArgumentException("destination must be a different buffer");
        }

        TaggedCapacityBuffer first = lockOrder < destination.lockOrder ? this : destination;
        TaggedCapacityBuffer second = first == this ? destination : this;

        synchronized (first) {
            synchronized (second) {
                if (closed || destination.closed) {
                    return false;
                }

                Node previous = null;
                Node candidate = head;
                while (candidate != null && !hasTag(candidate.message.tags, tag)) {
                    previous = candidate;
                    candidate = candidate.next;
                }
                if (candidate == null
                        || candidate.message.payload.length > destination.capacity - destination.occupancy) {
                    return false;
                }

                removeNode(previous, candidate);
                destination.append(candidate);
                notifyAll();
                destination.notifyAll();
                return true;
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
            for (Node node = head; node != null; node = node.next) {
                count++;
            }

            Message[] messages = new Message[count];
            int index = 0;
            for (Node node = head; node != null; node = node.next) {
                messages[index++] = node.message.detachedCopy();
            }
            return new Snapshot(messages, occupancy, capacity, closed);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("buffer is closed");
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

    private void removeHead() {
        removeNode(null, head);
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

    private static long allocateMessageId() {
        synchronized (MESSAGE_ID_LOCK) {
            if (nextMessageId <= 0) {
                throw new IllegalStateException("message ID space exhausted");
            }
            return nextMessageId++;
        }
    }

    private static byte[] copyPayload(byte[] payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        if (payload.length == 0) {
            throw new IllegalArgumentException("payload must not be empty");
        }
        return payload.clone();
    }

    private static String[] copyTags(String[] tags) {
        if (tags == null) {
            throw new IllegalArgumentException("tags must not be null");
        }
        String[] copy = tags.clone();
        for (String tag : copy) {
            requireTag(tag);
        }
        return copy;
    }

    private static void requireTag(String tag) {
        if (tag == null) {
            throw new IllegalArgumentException("tag must not be null");
        }
    }

    private static boolean hasTag(String[] tags, String sought) {
        for (String tag : tags) {
            if (tag.equals(sought)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAnyTag(String[] tags, String[] soughtTags) {
        for (String tag : tags) {
            for (String sought : soughtTags) {
                if (tag.equals(sought)) {
                    return true;
                }
            }
        }
        return false;
    }

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
            this.payload = payload.clone();
            this.tags = tags.clone();
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

        private Message detachedCopy() {
            return new Message(id, payload, tags);
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

        public Message[] getMessages() {
            Message[] copy = new Message[messages.length];
            for (int i = 0; i < messages.length; i++) {
                copy[i] = messages[i].detachedCopy();
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

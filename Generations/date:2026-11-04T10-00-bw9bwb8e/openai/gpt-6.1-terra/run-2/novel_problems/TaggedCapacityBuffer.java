@TaggedCapacityBuffer.ThreadSafe
public final class TaggedCapacityBuffer {
    @interface ThreadSafe {}

    private static final Object ID_LOCK = new Object();
    private static long nextId = 1;
    private static boolean idsExhausted;

    // Serializes transfers so that acquiring two buffer monitors cannot deadlock.
    private static final Object TRANSFER_LOCK = new Object();

    private final Object lock = new Object();
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

        private Snapshot(Message[] messages, int occupancy, int capacity,
                         boolean closed) {
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

        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("buffer is closed");
            }

            long id = allocateId();
            Node added = new Node(id, payloadCopy, tagsCopy);

            while (payloadCopy.length > capacity - occupancy) {
                Node previous = null;
                Node current = head;

                while (current != null
                        && !sharesAnyTag(current.tags, evictionTagsCopy)) {
                    previous = current;
                    current = current.next;
                }

                if (current == null) {
                    previous = null;
                    current = head;
                }
                unlink(previous, current);
            }

            append(added);
            lock.notifyAll();
            return id;
        }
    }

    public long putBlocking(byte[] payload, String[] tags)
            throws InterruptedException {
        byte[] payloadCopy = copyPayload(payload);
        String[] tagsCopy = copyTags(tags);

        synchronized (lock) {
            while (!closed && payloadCopy.length > capacity - occupancy) {
                lock.wait();
            }
            if (closed) {
                throw new IllegalStateException("buffer is closed");
            }

            long id = allocateId();
            Node added = new Node(id, payloadCopy, tagsCopy);
            append(added);
            lock.notifyAll();
            return id;
        }
    }

    public Message takeMatching(String tag) throws InterruptedException {
        if (tag == null) {
            throw new NullPointerException("tag");
        }

        synchronized (lock) {
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
        if (destination == null) {
            throw new NullPointerException("destination");
        }
        if (tag == null) {
            throw new NullPointerException("tag");
        }
        if (destination == this) {
            throw new IllegalArgumentException("destination is this buffer");
        }

        synchronized (TRANSFER_LOCK) {
            synchronized (lock) {
                synchronized (destination.lock) {
                    if (closed || destination.closed) {
                        return false;
                    }

                    Node previous = null;
                    Node current = head;
                    while (current != null && !hasTag(current.tags, tag)) {
                        previous = current;
                        current = current.next;
                    }

                    if (current == null
                            || current.payload.length
                            > destination.capacity - destination.occupancy) {
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
            if (copy[i] == null) {
                throw new NullPointerException("null tag");
            }
        }
        return copy;
    }

    private static long allocateId() {
        synchronized (ID_LOCK) {
            if (idsExhausted) {
                throw new IllegalStateException("message IDs exhausted");
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

    // Called only while holding this buffer's monitor.
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

    // Called only while holding this buffer's monitor.
    private void unlink(Node previous, Node node) {
        if (previous == null) {
            head = node.next;
        } else {
            previous.next = node.next;
        }
        if (tail == node) {
            tail = previous;
        }
        node.next = null;
        occupancy -= node.payload.length;
    }
}

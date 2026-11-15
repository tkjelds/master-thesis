@TaggedCapacityBuffer.ThreadSafe
public final class TaggedCapacityBuffer {
    @interface ThreadSafe {}

    private static final Object SEQUENCE_LOCK = new Object();
    private static long lastMessageId;
    private static long lastBufferOrder;

    private final Object lock = new Object();
    private final int capacity;
    private final long order;
    private Node head;
    private Node tail;
    private int occupancy;
    private int count;
    private boolean closed;

    private static final class Node {
        final Message message;
        Node next;

        Node(Message message) {
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
            return copyTags(tags);
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
            Message[] result = new Message[messages.length];
            for (int i = 0; i < messages.length; i++) {
                result[i] = messages[i];
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

    public TaggedCapacityBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        synchronized (SEQUENCE_LOCK) {
            if (lastBufferOrder == 9223372036854775807L) {
                throw new IllegalStateException("buffer order exhausted");
            }
            order = ++lastBufferOrder;
        }
    }

    public long offer(byte[] payload, String[] tags, String[] evictionTags) {
        byte[] payloadCopy = checkedPayload(payload);
        String[] tagsCopy = copyTags(tags);
        String[] evictionTagsCopy = copyTags(evictionTags);
        synchronized (lock) {
            ensureOpen();
            long id = allocateMessageId();
            Node inserted = new Node(new Message(id, payloadCopy, tagsCopy));

            Node previous = null;
            Node current = head;
            while (current != null && occupancy > capacity - payloadCopy.length) {
                Node next = current.next;
                if (sharesTag(current.message.tags, evictionTagsCopy)) {
                    remove(previous, current);
                } else {
                    previous = current;
                }
                current = next;
            }
            while (occupancy > capacity - payloadCopy.length) {
                remove(null, head);
            }
            append(inserted);
            lock.notifyAll();
            return id;
        }
    }

    public long putBlocking(byte[] payload, String[] tags)
            throws InterruptedException {
        byte[] payloadCopy = checkedPayload(payload);
        String[] tagsCopy = copyTags(tags);
        synchronized (lock) {
            while (!closed && occupancy > capacity - payloadCopy.length) {
                lock.wait();
            }
            ensureOpen();
            long id = allocateMessageId();
            append(new Node(new Message(id, payloadCopy, tagsCopy)));
            lock.notifyAll();
            return id;
        }
    }

    public Message takeMatching(String tag) throws InterruptedException {
        requireTag(tag);
        synchronized (lock) {
            while (true) {
                Node previous = null;
                Node current = head;
                while (current != null && !hasTag(current.message.tags, tag)) {
                    previous = current;
                    current = current.next;
                }
                if (current != null) {
                    Message result = detached(current.message);
                    remove(previous, current);
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
            throw new IllegalArgumentException("destination must not be null");
        }
        if (destination == this) {
            throw new IllegalArgumentException("destination must differ from source");
        }
        requireTag(tag);
        TaggedCapacityBuffer first = order < destination.order ? this : destination;
        TaggedCapacityBuffer second = first == this ? destination : this;
        synchronized (first.lock) {
            synchronized (second.lock) {
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
                    destination.occupancy > destination.capacity - current.message.payload.length) {
                    return false;
                }
                remove(previous, current);
                destination.append(current);
                lock.notifyAll();
                destination.lock.notifyAll();
                return true;
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
            Message[] messages = new Message[count];
            Node current = head;
            for (int i = 0; i < messages.length; i++) {
                messages[i] = detached(current.message);
                current = current.next;
            }
            return new Snapshot(messages, occupancy, capacity, closed);
        }
    }

    private byte[] checkedPayload(byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > capacity) {
            throw new IllegalArgumentException("invalid payload");
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
            throw new IllegalArgumentException("tags must not be null");
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
            throw new IllegalArgumentException("tag must not be null");
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

    private static boolean sharesTag(String[] tags, String[] candidates) {
        for (int i = 0; i < candidates.length; i++) {
            if (hasTag(tags, candidates[i])) {
                return true;
            }
        }
        return false;
    }

    private static Message detached(Message message) {
        return new Message(message.id, copyBytes(message.payload), copyTags(message.tags));
    }

    private static long allocateMessageId() {
        synchronized (SEQUENCE_LOCK) {
            if (lastMessageId == 9223372036854775807L) {
                throw new IllegalStateException("message IDs exhausted");
            }
            return ++lastMessageId;
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
        count++;
    }

    private void remove(Node previous, Node node) {
        if (previous == null) {
            head = node.next;
        } else {
            previous.next = node.next;
        }
        if (tail == node) {
            tail = previous;
        }
        node.next = null;
        occupancy -= node.message.payload.length;
        count--;
    }
}

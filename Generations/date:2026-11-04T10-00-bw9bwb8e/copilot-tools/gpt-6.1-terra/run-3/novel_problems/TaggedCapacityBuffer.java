@TaggedCapacityBuffer.ThreadSafe
public final class TaggedCapacityBuffer {
    @interface ThreadSafe {}

    private static final Object ID_LOCK = new Object();
    private static final Object MOVE_LOCK = new Object();
    private static long lastId;

    private final Object lock = new Object();
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
        byte[] payloadCopy = copyPayload(payload);
        String[] tagsCopy = copyTags(tags);
        String[] evictionTagsCopy = copyTags(evictionTags);

        synchronized (lock) {
            ensureOpen();
            Node node = new Node(nextId(), payloadCopy, tagsCopy);
            Node previous = null;
            Node current = head;

            while (current != null && !fits(payloadCopy.length)) {
                Node next = current.next;
                if (sharesTag(current.tags, evictionTagsCopy)) {
                    unlink(previous, current);
                } else {
                    previous = current;
                }
                current = next;
            }

            while (!fits(payloadCopy.length)) {
                unlink(null, head);
            }

            append(node);
            lock.notifyAll();
            return node.id;
        }
    }

    public long putBlocking(byte[] payload, String[] tags)
            throws InterruptedException {
        byte[] payloadCopy = copyPayload(payload);
        String[] tagsCopy = copyTags(tags);

        synchronized (lock) {
            while (!closed && !fits(payloadCopy.length)) {
                lock.wait();
            }
            ensureOpen();

            Node node = new Node(nextId(), payloadCopy, tagsCopy);
            append(node);
            lock.notifyAll();
            return node.id;
        }
    }

    public Message takeMatching(String tag) throws InterruptedException {
        requireTag(tag);

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
        if (destination == this) {
            throw new IllegalArgumentException("destination must differ from source");
        }
        requireTag(tag);

        synchronized (MOVE_LOCK) {
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

                    if (current == null || !destination.fits(current.payload.length)) {
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
            for (Node node = head; node != null; node = node.next) {
                count++;
            }

            Message[] messages = new Message[count];
            int index = 0;
            for (Node node = head; node != null; node = node.next) {
                messages[index++] = new Message(node);
            }
            return new Snapshot(messages, occupancy, capacity, closed);
        }
    }

    public static final class Message {
        private final long id;
        private final byte[] payload;
        private final String[] tags;

        private Message(Node node) {
            id = node.id;
            payload = copyBytes(node.payload);
            tags = copyStrings(node.tags);
        }

        public long id() {
            return id;
        }

        public byte[] payload() {
            return copyBytes(payload);
        }

        public String[] tags() {
            return copyStrings(tags);
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

        public Message[] messages() {
            Message[] copy = new Message[messages.length];
            for (int i = 0; i < messages.length; i++) {
                copy[i] = messages[i];
            }
            return copy;
        }

        public int occupancy() {
            return occupancy;
        }

        public int capacity() {
            return capacity;
        }

        public boolean isClosed() {
            return closed;
        }
    }

    private static final class Node {
        private final long id;
        private final byte[] payload;
        private final String[] tags;
        private Node next;

        private Node(long id, byte[] payload, String[] tags) {
            this.id = id;
            this.payload = payload;
            this.tags = tags;
        }
    }

    private byte[] copyPayload(byte[] payload) {
        if (payload == null) {
            throw new NullPointerException("payload");
        }
        if (payload.length == 0 || payload.length > capacity) {
            throw new IllegalArgumentException("invalid payload length");
        }
        return copyBytes(payload);
    }

    private static String[] copyTags(String[] tags) {
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

    private static long nextId() {
        synchronized (ID_LOCK) {
            if (lastId == Long.MAX_VALUE) {
                throw new IllegalStateException("message IDs exhausted");
            }
            return ++lastId;
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

    private static boolean sharesTag(String[] tags, String[] otherTags) {
        for (int i = 0; i < otherTags.length; i++) {
            if (hasTag(tags, otherTags[i])) {
                return true;
            }
        }
        return false;
    }

    private boolean fits(int length) {
        return length <= capacity - occupancy;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("buffer is closed");
        }
    }

    private void append(Node node) {
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
        node.next = null;
        occupancy -= node.payload.length;
    }
}

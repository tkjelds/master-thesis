import java.lang.reflect.Array;

@TaggedCapacityBuffer.ThreadSafe
public class TaggedCapacityBuffer {
    @interface ThreadSafe {}


    private static final Object ID_LOCK = new Object();
    private static long nextMessageId = 1L;
    private static long nextBufferSeq = 1L;

    private final long seq;
    private final int capacity;

    private Message head;
    private Message tail;
    private int occupancy;
    private boolean closed;

    public TaggedCapacityBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        synchronized (ID_LOCK) {
            this.seq = nextBufferSeq++;
        }
    }

    public long offer(byte[] payload, String[] tags, String[] evictionTags) {
        validatePayload(payload);
        validateTags(tags);
        validateTags(evictionTags);
        int len = payload.length;
        Message m = new Message(nextMessageId(), payload, tags);

        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("buffer is closed");
            }
            // Pass 1: evict oldest messages sharing an eviction tag.
            Message cur = head;
            while (occupancy + len > capacity && cur != null) {
                Message next = cur.next;
                if (sharesAnyTag(cur.tags, evictionTags)) {
                    unlink(cur);
                    occupancy -= cur.payload.length;
                }
                cur = next;
            }
            // Pass 2: evict remaining oldest messages.
            cur = head;
            while (occupancy + len > capacity && cur != null) {
                Message next = cur.next;
                unlink(cur);
                occupancy -= cur.payload.length;
                cur = next;
            }
            linkTail(m);
            occupancy += len;
            notifyAll();
            return m.id;
        }
    }

    public long putBlocking(byte[] payload, String[] tags) throws InterruptedException {
        validatePayload(payload);
        validateTags(tags);
        int len = payload.length;
        Message m = new Message(nextMessageId(), payload, tags);

        synchronized (this) {
            while (true) {
                if (closed) {
                    throw new IllegalStateException("buffer is closed");
                }
                if (occupancy + len <= capacity) {
                    break;
                }
                wait();
            }
            linkTail(m);
            occupancy += len;
            notifyAll();
            return m.id;
        }
    }

    public Message takeMatching(String tag) throws InterruptedException {
        if (tag == null) {
            throw new NullPointerException("tag");
        }
        synchronized (this) {
            while (true) {
                Message found = findOldest(tag);
                if (found != null) {
                    unlink(found);
                    occupancy -= found.payload.length;
                    notifyAll();
                    return found;
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
            throw new NullPointerException("destination");
        }
        if (tag == null) {
            throw new NullPointerException("tag");
        }
        if (destination == this) {
            throw new IllegalArgumentException("destination must differ from source");
        }
        TaggedCapacityBuffer first = this.seq < destination.seq ? this : destination;
        TaggedCapacityBuffer second = (first == this) ? destination : this;

        synchronized (first) {
            synchronized (second) {
                if (this.closed || destination.closed) {
                    return false;
                }
                Message m = findOldest(tag);
                if (m == null) {
                    return false;
                }
                if (destination.occupancy + m.payload.length > destination.capacity) {
                    return false;
                }
                unlink(m);
                occupancy -= m.payload.length;
                destination.linkTail(m);
                destination.occupancy += m.payload.length;
                this.notifyAll();
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
            int count = 0;
            for (Message c = head; c != null; c = c.next) {
                count++;
            }
            Message[] msgs = new Message[count];
            int i = 0;
            for (Message c = head; c != null; c = c.next) {
                msgs[i++] = new Message(c.id, c.payload, c.tags);
            }
            return new Snapshot(msgs, occupancy, capacity, closed);
        }
    }

    // ---- internal helpers (caller must hold this buffer's monitor) ----

    private Message findOldest(String tag) {
        for (Message c = head; c != null; c = c.next) {
            if (hasTag(c.tags, tag)) {
                return c;
            }
        }
        return null;
    }

    private void linkTail(Message m) {
        m.prev = tail;
        m.next = null;
        if (tail == null) {
            head = m;
        } else {
            tail.next = m;
        }
        tail = m;
    }

    private void unlink(Message m) {
        if (m.prev == null) {
            head = m.next;
        } else {
            m.prev.next = m.next;
        }
        if (m.next == null) {
            tail = m.prev;
        } else {
            m.next.prev = m.prev;
        }
        m.prev = null;
        m.next = null;
    }

    private static boolean hasTag(String[] tags, String tag) {
        for (String t : tags) {
            if (tag.equals(t)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sharesAnyTag(String[] tags, String[] candidates) {
        for (String c : candidates) {
            if (hasTag(tags, c)) {
                return true;
            }
        }
        return false;
    }

    private void validatePayload(byte[] payload) {
        if (payload == null) {
            throw new NullPointerException("payload");
        }
        if (payload.length == 0) {
            throw new IllegalArgumentException("payload must not be empty");
        }
        if (payload.length > capacity) {
            throw new IllegalArgumentException("payload exceeds capacity");
        }
    }

    private static void validateTags(String[] tags) {
        if (tags == null) {
            throw new NullPointerException("tags");
        }
        for (String t : tags) {
            if (t == null) {
                throw new NullPointerException("tag element");
            }
        }
    }

    private static long nextMessageId() {
        synchronized (ID_LOCK) {
            return nextMessageId++;
        }
    }

    // ---- public nested types ----

    public static final class Message {
        private final long id;
        private final byte[] payload;
        private final String[] tags;
        private Message prev;
        private Message next;

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
            this.messages = messages;
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

        public boolean isClosed() {
            return closed;
        }
    }
}

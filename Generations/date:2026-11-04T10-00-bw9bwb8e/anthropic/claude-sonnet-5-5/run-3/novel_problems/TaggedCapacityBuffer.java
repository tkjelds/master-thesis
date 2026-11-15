import java.lang.*;

@TaggedCapacityBuffer.ThreadSafe
public class TaggedCapacityBuffer {
    @interface ThreadSafe {}


    // ---- global id allocation (buffer ordering and message ids) ----
    private static final Object ID_LOCK = new Object();
    private static long nextMessageId = 1;
    private static long nextBufferId = 1;

    private static long allocMessageId() {
        synchronized (ID_LOCK) {
            return nextMessageId++;
        }
    }

    private static long allocBufferId() {
        synchronized (ID_LOCK) {
            return nextBufferId++;
        }
    }

    /** Immutable message; accessors return copies. */
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
            return payload.clone();
        }

        public String[] getTags() {
            return tags.clone();
        }

        public int size() {
            return payload.length;
        }

        private boolean hasTag(String tag) {
            for (int i = 0; i < tags.length; i++) {
                if (tags[i].equals(tag)) return true;
            }
            return false;
        }

        private boolean sharesAny(String[] other) {
            for (int i = 0; i < other.length; i++) {
                if (hasTag(other[i])) return true;
            }
            return false;
        }
    }

    /** Coherent detached view of the buffer. */
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

    private final Object lock = new Object();
    private final long bufferId = allocBufferId();
    private final int capacity;
    private Message[] items = new Message[16];
    private int count = 0;
    private int occupancy = 0;
    private boolean closed = false;

    public TaggedCapacityBuffer(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    // ---- helpers (call with lock held) ----
    private static String[] copyTags(String[] tags, String what) {
        if (tags == null) throw new IllegalArgumentException(what + " is null");
        String[] c = tags.clone();
        for (int i = 0; i < c.length; i++) {
            if (c[i] == null) throw new IllegalArgumentException(what + " contains null");
        }
        return c;
    }

    private Message makeMessage(byte[] payload, String[] tags) {
        if (payload == null) throw new IllegalArgumentException("payload is null");
        if (payload.length == 0) throw new IllegalArgumentException("payload is empty");
        if (payload.length > capacity) throw new IllegalArgumentException("payload exceeds capacity");
        String[] t = copyTags(tags, "tags");
        return new Message(allocMessageId(), payload.clone(), t);
    }

    private void append(Message m) {
        if (count == items.length) {
            Message[] n = new Message[items.length * 2];
            for (int i = 0; i < count; i++) n[i] = items[i];
            items = n;
        }
        items[count++] = m;
        occupancy += m.payload.length;
    }

    private int indexOfTag(String tag) {
        for (int i = 0; i < count; i++) {
            if (items[i].hasTag(tag)) return i;
        }
        return -1;
    }

    private Message removeAt(int idx) {
        Message m = items[idx];
        for (int i = idx; i < count - 1; i++) items[i] = items[i + 1];
        items[--count] = null;
        occupancy -= m.payload.length;
        return m;
    }

    // ---- public API ----
    public long offer(byte[] payload, String[] tags, String[] evictionTags) {
        if (payload == null || tags == null || evictionTags == null) {
            throw new IllegalArgumentException("null input");
        }
        String[] ev = copyTags(evictionTags, "evictionTags");
        Message m = makeMessage(payload, tags);
        synchronized (lock) {
            if (closed) throw new IllegalStateException("buffer closed");
            int need = occupancy + m.payload.length - capacity;
            if (need > 0) {
                // pass 1: evict oldest messages sharing an eviction tag
                int w = 0;
                int oldCount = count;
                for (int r = 0; r < oldCount; r++) {
                    Message it = items[r];
                    if (need > 0 && it.sharesAny(ev)) {
                        need -= it.payload.length;
                        occupancy -= it.payload.length;
                    } else {
                        items[w++] = it;
                    }
                }
                for (int i = w; i < oldCount; i++) items[i] = null;
                count = w;
                // pass 2: evict remaining oldest
                if (need > 0) {
                    int k = 0;
                    while (need > 0 && k < count) {
                        need -= items[k].payload.length;
                        occupancy -= items[k].payload.length;
                        k++;
                    }
                    int newCount = count - k;
                    for (int i = 0; i < newCount; i++) items[i] = items[i + k];
                    for (int i = newCount; i < count; i++) items[i] = null;
                    count = newCount;
                }
            }
            append(m);
            lock.notifyAll();
            return m.id;
        }
    }

    public long putBlocking(byte[] payload, String[] tags) throws InterruptedException {
        if (payload == null || tags == null) throw new IllegalArgumentException("null input");
        Message m = makeMessage(payload, tags);
        synchronized (lock) {
            while (true) {
                if (closed) throw new IllegalStateException("buffer closed");
                if (capacity - occupancy >= m.payload.length) break;
                lock.wait();
            }
            append(m);
            lock.notifyAll();
            return m.id;
        }
    }

    public Message takeMatching(String tag) throws InterruptedException {
        if (tag == null) throw new IllegalArgumentException("tag is null");
        synchronized (lock) {
            while (true) {
                int idx = indexOfTag(tag);
                if (idx >= 0) {
                    Message m = removeAt(idx);
                    lock.notifyAll();
                    return m;
                }
                if (closed) return null;
                lock.wait();
            }
        }
    }

    public boolean moveTo(TaggedCapacityBuffer destination, String tag) {
        if (destination == null) throw new IllegalArgumentException("destination is null");
        if (destination == this) throw new IllegalArgumentException("destination is this");
        if (tag == null) throw new IllegalArgumentException("tag is null");
        TaggedCapacityBuffer first = this.bufferId < destination.bufferId ? this : destination;
        TaggedCapacityBuffer second = first == this ? destination : this;
        synchronized (first.lock) {
            synchronized (second.lock) {
                if (closed || destination.closed) return false;
                int idx = indexOfTag(tag);
                if (idx < 0) return false;
                Message m = items[idx];
                if (destination.capacity - destination.occupancy < m.payload.length) return false;
                removeAt(idx);
                destination.append(m);
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
            Message[] copy = new Message[count];
            for (int i = 0; i < count; i++) copy[i] = items[i];
            return new Snapshot(copy, occupancy, capacity, closed);
        }
    }
}

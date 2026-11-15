@TaggedCapacityBuffer.ThreadSafe
public class TaggedCapacityBuffer {
    @interface ThreadSafe {}

    private final byte[] buffer;
    private final String[][] tags;
    private int head = 0;
    private int tail = 0;
    private int occupancy = 0;
    private boolean closed = false;

    public TaggedCapacityBuffer(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Non-positive capacity");
        buffer = new byte[capacity];
        tags = new String[capacity][];
    }

    public long offer(byte[] payload, String[] tags, String[] evictionTags) {
        synchronized (this) {
            if (closed) throw new IllegalStateException("Buffer closed");
            while (!canInsert(payload.length, evictionTags)) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Producer interrupted", e);
                }
            }
            long id = generateId();
            insert(payload, tags, id);
            notifyAll();
            return id;
        }
    }

    public long putBlocking(byte[] payload, String[] tags) {
        synchronized (this) {
            if (closed) throw new IllegalStateException("Buffer closed");
            while (!canInsert(payload.length, null)) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Producer interrupted", e);
                }
            }
            long id = generateId();
            insert(payload, tags, id);
            notifyAll();
            return id;
        }
    }

    public byte[] takeMatching(String tag) {
        synchronized (this) {
            while (!hasTag(tag)) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            int index = findTag(tag);
            byte[] payload = detach(index, tags[index]);
            notifyAll();
            return payload;
        }
    }

    public boolean moveTo(TaggedCapacityBuffer destination, String tag) {
        if (this == destination) throw new IllegalArgumentException("Cannot move to self");
        synchronized (this) {
            if (closed || destination.closed) return false;
            while (!hasTag(tag)) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }

        synchronized (destination) {
            if (destination.closed) return false;
            int index = findTag(tag);
            byte[] payload = detach(index, tags[index]);
            destination.insert(payload, tags[index], index);
        }

        synchronized (this) {
            notifyAll();
            return true;
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
            for (int i = head; i != tail; ) {
                if (tags[i] != null && !tags[i].isEmpty()) count++;
                i = (i + 1) % buffer.length;
            }
            byte[] snapshotBuffer = new byte[count];
            String[][] snapshotTags = new String[count][];
            int j = 0;
            for (int i = head; i != tail; ) {
                if (tags[i] != null && !tags[i].isEmpty()) {
                    snapshotBuffer[j] = buffer[i];
                    snapshotTags[j++] = tags[i].clone();
                }
                i = (i + 1) % buffer.length;
            }
            return new Snapshot(snapshotBuffer, snapshotTags, occupancy, closed);
        }
    }

    private boolean canInsert(int payloadSize, String[] evictionTags) {
        return closed || (occupancy + payloadSize <= buffer.length &&
                (evictionTags == null || findMatchingTag(evictionTags) != -1));
    }

    private boolean hasTag(String tag) {
        int i = head;
        while (i != tail) {
            if (tags[i] != null && tags[i].contains(tag)) return true;
            i = (i + 1) % buffer.length;
        }
        return false;
    }

    private int findTag(String tag) {
        int i = head;
        while (i != tail) {
            if (tags[i] != null && tags[i].contains(tag)) return i;
            i = (i + 1) % buffer.length;
        }
        return -1;
    }

    private int findMatchingTag(String[] tags) {
        for (int i = head; i != tail; ) {
            if (this.tags[i] != null && containsSubset(this.tags[i], tags)) return i;
            i = (i + 1) % buffer.length;
        }
        return -1;
    }

    private boolean containsSubset(String[] a, String[] b) {
        for (String tag : b) {
            if (!a.contains(tag)) return false;
        }
        return true;
    }

    private void insert(byte[] payload, String[] tags, long id) {
        buffer[tail] = payload;
        this.tags[tail] = tags.clone();
        tail = (tail + 1) % buffer.length;
        occupancy += payload.length;
    }

    private byte[] detach(int index, String[] tags) {
        byte[] payload = buffer[index];
        this.tags[index] = null;
        head = (head + 1) % buffer.length;
        occupancy -= payload.length;
        return payload;
    }

    private long generateId() {
        return System.nanoTime();
    }

    public static class Snapshot {
        private final byte[] buffer;
        private final String[][] tags;
        private final int occupancy;
        private final boolean closed;

        public Snapshot(byte[] buffer, String[][] tags, int occupancy, boolean closed) {
            this.buffer = buffer;
            this.tags = tags;
            this.occupancy = occupancy;
            this.closed = closed;
        }

        public byte[] getBuffer() {
            return buffer;
        }

        public String[][] getTags() {
            return tags;
        }

        public int getOccupancy() {
            return occupancy;
        }

        public boolean isClosed() {
            return closed;
        }
    }
}

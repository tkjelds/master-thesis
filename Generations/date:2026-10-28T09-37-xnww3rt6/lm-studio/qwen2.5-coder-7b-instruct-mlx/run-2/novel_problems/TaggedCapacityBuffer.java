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
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        buffer = new byte[capacity];
        tags = new String[capacity][];
    }

    public long offer(byte[] payload, String[] tags, String[] evictionTags) {
        if (payload == null || tags == null || payload.length > buffer.length) {
            throw new IllegalArgumentException("Invalid input");
        }
        synchronized (this) {
            while (isFull()) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Buffer closed", e);
                }
            }
            return doOffer(payload, tags, evictionTags);
        }
    }

    public void putBlocking(byte[] payload, String[] tags) {
        if (payload == null || tags == null || payload.length > buffer.length) {
            throw new IllegalArgumentException("Invalid input");
        }
        synchronized (this) {
            while (isFull()) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Buffer closed", e);
                }
            }
            doOffer(payload, tags, null);
        }
    }

    private long doOffer(byte[] payload, String[] tags, String[] evictionTags) {
        if (closed) {
            throw new IllegalStateException("Buffer closed");
        }
        long id = System.currentTimeMillis() + head;
        while (evictionTags != null && evictMatching(evictionTags)) {
            if (isFull()) {
                break;
            }
        }
        while (isFull()) {
            wait();
        }
        buffer[tail] = payload;
        this.tags[tail] = tags.clone();
        tail = (tail + 1) % buffer.length;
        occupancy += payload.length;
        notifyAll();
        return id;
    }

    private boolean evictMatching(String[] evictionTags) {
        for (int i = head; i != tail; i = (i + 1) % buffer.length) {
            String[] currentTags = tags[i];
            for (String evictionTag : evictionTags) {
                for (String currentTag : currentTags) {
                    if (currentTag.equals(evictionTag)) {
                        reclaimCapacity(currentTags);
                        buffer[i] = null;
                        tags[i] = null;
                        head = (i + 1) % buffer.length;
                        occupancy -= currentTags[0].length();
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void reclaimCapacity(String[] tags) {
        for (String tag : tags) {
            System.gc();
        }
    }

    public byte[] takeMatching(String tag) {
        synchronized (this) {
            while (isEmpty() && !closed) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            if (isEmpty() || closed) {
                return null;
            }
            for (int i = head; i != tail; i = (i + 1) % buffer.length) {
                String[] currentTags = tags[i];
                if (currentTags != null && contains(currentTags, tag)) {
                    byte[] payload = buffer[i];
                    reclaimCapacity(currentTags);
                    buffer[i] = null;
                    tags[i] = null;
                    head = (i + 1) % buffer.length;
                    occupancy -= currentTags[0].length();
                    notifyAll();
                    return payload;
                }
            }
        }
        return null;
    }

    private boolean contains(String[] array, String element) {
        for (Stringstr : array) {
            if (str.equals(element)) {
                return true;
            }
        }
        return false;
    }

    public boolean moveTo(TaggedCapacityBuffer destination, String tag) {
        if (destination == this || closed || !destination.hasCapacity()) {
            return false;
        }
        synchronized (this) {
            while (isEmpty() && !closed) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            if (isEmpty() || closed) {
                return false;
            }
        }
        synchronized (destination) {
            while (destination.isFull()) {
                try {
                    destination.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            for (int i = head; i != tail; i = (i + 1) % buffer.length) {
                String[] currentTags = tags[i];
                if (currentTags != null && contains(currentTags, tag)) {
                    byte[] payload = buffer[i];
                    reclaimCapacity(currentTags);
                    buffer[i] = null;
                    tags[i] = null;
                    head = (i + 1) % buffer.length;
                    occupancy -= currentTags[0].length();
                    destination.offer(payload, tags[i], null);
                    return true;
                }
            }
        }
        return false;
    }

    public void close() {
        synchronized (this) {
            closed = true;
            notifyAll();
        }
    }

    public Snapshot snapshot() {
        synchronized (this) {
            return new Snapshot(head, tail, occupancy, buffer, tags, closed);
        }
    }

    private boolean isFull() {
        return (tail + 1) % buffer.length == head;
    }

    private boolean isEmpty() {
        return tail == head;
    }

    private boolean hasCapacity() {
        return (tail + 1) % buffer.length != head;
    }

    public static class Snapshot {
        private final int head;
        private final int tail;
        private final int occupancy;
        private final byte[] buffer;
        private final String[][] tags;
        private final boolean closed;

        public Snapshot(int head, int tail, int occupancy, byte[] buffer, String[][] tags, boolean closed) {
            this.head = head;
            this.tail = tail;
            this.occupancy = occupancy;
            this.buffer = buffer.clone();
            this.tags = tags.clone();
            this.closed = closed;
        }

        public int getHead() {
            return head;
        }

        public int getTail() {
            return tail;
        }

        public int getOccupancy() {
            return occupancy;
        }

        public byte[] getBuffer() {
            return buffer;
        }

        public String[][] getTags() {
            return tags;
        }

        public boolean isClosed() {
            return closed;
        }
    }
}

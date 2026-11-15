@TaggedCapacityBuffer.ThreadSafe
public class TaggedCapacityBuffer {
    @interface ThreadSafe {}

    private final byte[] buffer;
    private int head = 0, tail = 0, occupancy = 0, capacity;

    public TaggedCapacityBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        buffer = new byte[capacity];
    }

    public int offer(byte[] payload, String[] tags, String[] evictionTags) {
        if (payload == null || tags == null || evictionTags == null || payload.length == 0) {
            return -1;
        }
        if (payload.length > capacity) {
            evict(evictionTags);
        }
        if (occupancy + payload.length > capacity) {
            return -1;
        }
        int index = tail;
        for (int i = 0; i < payload.length; i++) {
            buffer[index] = payload[i];
            index = (index + 1) % capacity;
        }
        tail = index;
        occupancy += payload.length;
        return head;
    }

    public synchronized void putBlocking(byte[] payload, String[] tags) throws InterruptedException {
        while (true) {
            if (offer(payload, tags, new String[0]) != -1) {
                break;
            }
            wait();
        }
    }

    public byte[] takeMatching(String tag) throws InterruptedException {
        while (true) {
            synchronized (this) {
                if (head == tail) {
                    wait();
                    continue;
                }
                int index = head;
                while (index != tail) {
                    byte[] message = new byte[buffer[index]];
                    for (int i = 0; i < message.length; i++) {
                        message[i] = buffer[index];
                        index = (index + 1) % capacity;
                    }
                    if (new String(message).equals(tag)) {
                        head = index;
                        occupancy -= message.length;
                        notifyAll();
                        return message;
                    }
                }
            }
        }
    }

    public synchronized boolean moveTo(TaggedCapacityBuffer destination, String tag) {
        if (destination == this || destination.isFull()) {
            return false;
        }
        int index = head;
        while (index != tail) {
            byte[] message = new byte[buffer[index]];
            for (int i = 0; i < message.length; i++) {
                message[i] = buffer[index];
                index = (index + 1) % capacity;
            }
            if (new String(message).equals(tag)) {
                destination.offer(message, new String[0], new String[0]);
                head = index;
                occupancy -= message.length;
                notifyAll();
                destination.notifyAll();
                return true;
            }
        }
        return false;
    }

    public synchronized void close() {
        notifyAll();
    }

    public synchronized Snapshot snapshot() {
        byte[] snapshotBuffer = new byte[occupancy];
        int index = head, j = 0;
        while (index != tail) {
            for (int i = 0; i < buffer[index]; i++) {
                snapshotBuffer[j++] = buffer[index];
            }
            index = (index + 1) % capacity;
        }
        return new Snapshot(snapshotBuffer, occupancy, isClosed());
    }

    private boolean isFull() {
        return occupancy == capacity;
    }

    private void evict(String[] evictionTags) {
        int index = head;
        while (index != tail) {
            byte[] message = new byte[buffer[index]];
            for (int i = 0; i < message.length; i++) {
                message[i] = buffer[index];
                index = (index + 1) % capacity;
            }
            for (String tag : evictionTags) {
                if (new String(message).equals(tag)) {
                    head = index;
                    occupancy -= message.length;
                    break;
                }
            }
        }
    }

    public class Snapshot {
        private final byte[] buffer;
        private final int occupancy, closed;

        private Snapshot(byte[] buffer, int occupancy, boolean closed) {
            this.buffer = buffer;
            this.occupancy = occupancy;
            this.closed = closed ? 1 : 0;
        }

        public byte[] getBuffer() {
            return buffer;
        }

        public int getOccupancy() {
            return occupancy;
        }

        public boolean isClosed() {
            return closed == 1;
        }
    }
}

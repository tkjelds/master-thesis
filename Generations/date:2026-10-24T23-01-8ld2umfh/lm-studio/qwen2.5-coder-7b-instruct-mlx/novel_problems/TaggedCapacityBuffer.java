@TaggedCapacityBuffer.ThreadSafe
public class TaggedCapacityBuffer {
    @interface ThreadSafe {}

    private final byte[] buffer;
    private int head = 0;
    private int tail = 0;
    private int occupancy = 0;

    public TaggedCapacityBuffer(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        buffer = new byte[capacity];
    }

    public int offer(byte[] payload, String[] tags, String[] evictionTags) {
        checkInputs(payload, tags);
        int id = tail;
        while (true) {
            synchronized (this) {
                if (occupancy + payload.length > buffer.length) break;
                System.arraycopy(payload, 0, buffer, tail, payload.length);
                tail = (tail + payload.length) % buffer.length;
                occupancy += payload.length;
                return id;
            }
        }
        evict(evictionTags);
        synchronized (this) {
            if (occupancy + payload.length > buffer.length) return -1;
            System.arraycopy(payload, 0, buffer, tail, payload.length);
            tail = (tail + payload.length) % buffer.length;
            occupancy += payload.length;
            return id;
        }
    }

    private void evict(String[] evictionTags) {
        while (true) {
            synchronized (this) {
                if (occupancy == 0) return;
                byte[] oldest = getMessageAt(head);
                boolean shouldEvict = false;
                for (String tag : evictionTags) {
                    if (Arrays.asList(oldest).contains(tag)) {
                        shouldEvict = true;
                        break;
                    }
                }
                if (!shouldEvict) return;
                head = (head + oldest.length) % buffer.length;
                occupancy -= oldest.length;
            }
        }
    }

    public void putBlocking(byte[] payload, String[] tags) throws InterruptedException {
        while (true) {
            synchronized (this) {
                if (occupancy + payload.length <= buffer.length) {
                    System.arraycopy(payload, 0, buffer, tail, payload.length);
                    tail = (tail + payload.length) % buffer.length;
                    occupancy += payload.length;
                    return;
                }
            }
            this.wait();
        }
    }

    public byte[] takeMatching(String tag) throws InterruptedException {
        while (true) {
            synchronized (this) {
                if (occupancy == 0) return null;
                byte[] oldest = getMessageAt(head);
                if (Arrays.asList(oldest).contains(tag)) {
                    head = (head + oldest.length) % buffer.length;
                    occupancy -= oldest.length;
                    return detachMessage(oldest);
                }
            }
            this.wait();
        }
    }

    public boolean moveTo(TaggedCapacityBuffer destination, String tag) {
        if (destination == this) throw new IllegalArgumentException("cannot move to self");
        synchronized (this) {
            synchronized (destination) {
                byte[] oldest = getMessageAt(head);
                if (oldest == null || !Arrays.asList(oldest).contains(tag) || destination.occupancy + oldest.length > destination.buffer.length) return false;
                System.arraycopy(oldest, 0, destination.buffer, destination.tail, oldest.length);
                destination.tail = (destination.tail + oldest.length) % destination.buffer.length;
                destination.occupancy += oldest.length;
                head = (head + oldest.length) % buffer.length;
                occupancy -= oldest.length;
                return true;
            }
        }
    }

    public void close() {
        synchronized (this) {
            buffer[0] = 0;
            this.notifyAll();
        }
    }

    public byte[] snapshot() {
        synchronized (this) {
            return Arrays.copyOf(buffer, occupancy);
        }
    }

    private void checkInputs(byte[] payload, String[] tags) {
        if (payload == null || tags == null) throw new NullPointerException("inputs cannot be null");
        if (payload.length == 0) throw new IllegalArgumentException("payload cannot be empty");
    }

    private byte[] getMessageAt(int index) {
        int length = 0;
        for (int i = index; i < buffer.length && buffer[i] != 0; i++) {
            length++;
        }
        return Arrays.copyOfRange(buffer, index, index + length);
    }

    private byte[] detachMessage(byte[] message) {
        for (int i = 0; i < message.length; i++) {
            buffer[head + i] = (byte) 0;
        }
        head = (head + message.length) % buffer.length;
        occupancy -= message.length;
        return message;
    }
}

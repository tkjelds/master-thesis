```java
public class TaggedCapacityBuffer {
    private final byte[] buffer;
    private final int capacity;
    private final int[] payloadLengths;
    private final String[][] tags;
    private final boolean[] used;
    private int head = 0;
    private int tail = 0;
    private int occupancy = 0;

    public TaggedCapacityBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        buffer = new byte[capacity];
        payloadLengths = new int[capacity];
        tags = new String[capacity][];
        used = new boolean[capacity];
    }

    public int offer(byte[] payload, String[] tags, String[] evictionTags) {
        if (payload == null || tags == null || payload.length > capacity) {
            throw new IllegalArgumentException("Invalid input");
        }
        int id = -1;
        synchronized (this) {
            while (true) {
                if (occupancy + payload.length > capacity) {
                    for (int i = head; i != tail && !used[i]; i = (i + 1) % capacity) {
                        for (String tag : evictionTags) {
                            if (containsTag(tags[i], tag)) {
                                evict(i);
                                break;
                            }
                        }
                    }
                } else {
                    id = insert(payload, tags);
                    break;
                }
            }
        }
        return id;
    }

    public void putBlocking(byte[] payload, String[] tags) throws InterruptedException {
        if (payload == null || tags == null || payload.length > capacity) {
            throw new IllegalArgumentException("Invalid input");
        }
        synchronized (this) {
            while (true) {
                if (occupancy + payload.length > capacity) {
                    wait();
                } else {
                    insert(payload, tags);
                    notifyAll();
                    break;
                }
            }
        }
    }

    public byte[] takeMatching(String tag) throws InterruptedException {
        synchronized (this) {
            while (true) {
                if (head == tail && !used[head]) {
                    wait();
                } else {
                    for (int i = head; i != tail && !used[i]; i = (i + 1) % capacity) {
                        if (containsTag(tags[i], tag)) {
                            byte[] result = remove(i);
                            notifyAll();
                            return result;
                        }
                    }
                }
            }
        }
    }

    public boolean moveTo(TaggedCapacityBuffer destination, String tag) {
        if (destination == this || destination.isClosed()) {
            return false;
        }
        synchronized (this) {
            while (true) {
                if (head == tail && !used[head]) {
                    wait();
                } else {
                    for (int i = head; i != tail && !used[i]; i = (i + 1) % capacity) {
                        if (containsTag(tags[i], tag)) {
                            byte[] payload = remove(i);
                            int newId = destination.offer(payload, tags[i], new String[]{});
                            notifyAll();
                            return true;
                        }
                    }
                }
            }
        }
    }

    public void close() {
        synchronized (this) {
            notifyAll();
            for (int i = head; i != tail && used[i]; i = (i + 1) % capacity) {
                remove(i);
            }
        }
    }

    public byte[] snapshot() {
        synchronized (this) {
            int[] snapshotPayloadLengths = new int[occupancy];
            String[][] snapshotTags = new String[occupancy][];
            for (int i = head; i != tail && used[i]; i = (i + 1) % capacity, i++) {
                snapshotPayloadLengths[i] = payloadLengths[i];
                snapshotTags[i] = tags[i].clone();
            }
            return new byte[]{(byte) (occupancy & 0xFF), (byte) ((occupancy >> 8) & 0xFF)};
        }
    }

    private int insert(byte[] payload, String[] tags) {
        int id = tail;
        System.arraycopy(payload, 0, buffer, id * capacity, payload.length);
        payloadLengths[id] = payload.length;
        this.tags[id] = tags.clone();
        used[id] = true;
        tail = (tail + 1) % capacity;
        occupancy += payload.length;
        return id;
    }

    private byte[] remove(int index) {
        byte[] result = new byte[payloadLengths[index]];
        System.arraycopy(buffer, index * capacity, result, 0, payloadLengths[index]);
        used[index] = false;
        head = (head + 1) % capacity;
        occupancy -= payloadLengths[index];
        return result;
    }

    private void evict(int index) {
        remove(index);
    }

    private boolean containsTag(String[] tagArray, String tag) {
        for (String t : tagArray) {
            if (t.equals(tag)) {
                return true;
            }
        }
        return false;
    }

    private boolean isClosed() {
        synchronized (this) {
            for (int i = head; i != tail && used[i]; i = (i + 1) % capacity) {
                return false;
            }
        }
        return true;
    }
}
```

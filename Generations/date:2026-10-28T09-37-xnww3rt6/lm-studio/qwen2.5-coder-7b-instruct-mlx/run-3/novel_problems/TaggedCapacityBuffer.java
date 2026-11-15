```java
public class TaggedCapacityBuffer {
    private final byte[] data;
    private final String[][] tagList;
    private final String[] evictionTags;
    private int head = 0, tail = 0, size = 0, occupancy = 0;
    private boolean closed = false;

    public TaggedCapacityBuffer(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        data = new byte[capacity];
        tagList = new String[capacity][];
        evictionTags = null; // Not used in this implementation
    }

    public int offer(byte[] payload, String[] tags, String[] evictionTags) {
        if (closed) throw new IllegalStateException("Buffer is closed");
        if (payload == null || tags == null || payload.length == 0) return -1;
        if (payload.length > data.length) return -1;

        synchronized (this) {
            while (size + payload.length > data.length) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
            }
            data[tail] = payload;
            tagList[tail] = tags.clone();
            tail = (tail + 1) % data.length;
            size++;
            occupancy += payload.length;
            notifyAll();
            return head;
        }
    }

    public void putBlocking(byte[] payload, String[] tags) throws InterruptedException {
        if (payload == null || tags == null || payload.length == 0) return;
        if (payload.length > data.length) throw new IllegalStateException("Payload too large");

        synchronized (this) {
            while (size + payload.length > data.length) {
                wait();
            }
            data[tail] = payload;
            tagList[tail] = tags.clone();
            tail = (tail + 1) % data.length;
            size++;
            occupancy += payload.length;
            notifyAll();
        }
    }

    public byte[] takeMatching(String tag) throws InterruptedException {
        if (closed) return null;

        synchronized (this) {
            while (size == 0) {
                wait();
            }
            if (tagList[head][0].equals(tag)) {
                byte[] result = data[head];
                size--;
                head = (head + 1) % data.length;
                occupancy -= result.length;
                notifyAll();
                return result;
            } else {
                wait();
                return null;
            }
        }
    }

    public boolean moveTo(TaggedCapacityBuffer destination, String tag) {
        if (destination == this || closed || destination.closed) return false;

        synchronized (this) {
            while (size > 0 && destination.size + data[head].length > destination.data.length) {
                wait();
            }
        }

        synchronized (destination) {
            while (size > 0 && destination.size + data[head].length <= destination.data.length) {
                byte[] payload = data[head];
                String[] tags = tagList[head].clone();
                head = (head + 1) % data.length;
                size--;
                destination.data[destination.tail] = payload;
                destination.tagList[destination.tail] = tags.clone();
                destination.tail = (destination.tail + 1) % destination.data.length;
                destination.size++;
                destination.occupancy += payload.length;
            }
        }

        synchronized (this) {
            notifyAll();
        }

        synchronized (destination) {
            destination.notifyAll();
        }
        return true;
    }

    public void close() {
        synchronized (this) {
            closed = true;
            notifyAll();
        }
    }

    public Object[] snapshot() {
        synchronized (this) {
            byte[][] payloadArray = new byte[size][];
            String[][] tagListArray = new String[size][];
            int i = 0;
            for (int j = head; j != tail; j = (j + 1) % data.length) {
                payloadArray[i] = data[j];
                tagListArray[i] = tagList[j].clone();
                i++;
            }
            return new Object[]{payloadArray, tagListArray, occupancy, closed ? 1 : 0};
        }
    }
}
```

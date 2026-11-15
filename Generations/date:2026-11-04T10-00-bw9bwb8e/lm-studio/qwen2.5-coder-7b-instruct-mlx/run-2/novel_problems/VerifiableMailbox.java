@VerifiableMailbox.ThreadSafe
public class VerifiableMailbox {
    @interface ThreadSafe {}

    private final byte[] capacity;
    private byte[][] inbox;
    private int front = 0;
    private int rear = 0;
    private long nextId = 1;

    public VerifiableMailbox(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = new byte[capacity][];
    }

    public long receive(byte[] content, boolean draft) {
        synchronized (this) {
            while (occupancy() + content.length > capacity()) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Thread interrupted", e);
                }
            }

            if (content == null || content.length == 0) {
                return -1;
            }

            long id = nextId++;
            byte[] contentCopy = new byte[content.length];
            System.arraycopy(content, 0, contentCopy, 0, content.length);

            while (front != rear && hasDuplicate(contentCopy)) {
                byte[] evicted = inbox[front++];
                front %= capacity.length;
            }

            if (occupiedSpace() + contentCopy.length > capacity()) {
                throw new IllegalStateException("ID exhaustion");
            }

            inbox[rear++] = contentCopy;
            rear %= capacity.length;

            return id;
        }
    }

    private boolean hasDuplicate(byte[] content) {
        for (int i = front; i != rear; i++) {
            int index = i % capacity.length;
            if (inbox[index] != null && content.length == inbox[index].length) {
                boolean match = true;
                for (int j = 0; j < content.length; j++) {
                    if (content[j] != inbox[index][j]) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    return true;
                }
            }
        }
        return false;
    }

    public byte[] fetchIfMatch(long messageId, String validator) {
        synchronized (this) {
            if (messageId <= 0 || validator == null) {
                return null;
            }

            for (int i = front; i != rear; i++) {
                int index = i % capacity.length;
                if (inbox[index] != null && getMessageId(index) == messageId) {
                    String expectedValidator = draft(idToIndex(messageId)) ? "W-" + messageId : "S-" + messageId;
                    if (expectedValidator.equals(validator)) {
                        byte[] result = new byte[inbox[index].length];
                        System.arraycopy(inbox[index], 0, result, 0, inbox[index].length);
                        return result;
                    }
                }
            }

            return null;
        }
    }

    public int prune(long throughId) {
        synchronized (this) {
            if (throughId < 0) {
                return 0;
            }

            int removed = 0;
            while (front != rear && getMessageId(front) <= throughId) {
                byte[] evicted = inbox[front++];
                front %= capacity.length;
                removed++;
            }

            return removed;
        }
    }

    public long snapshotHeader() {
        synchronized (this) {
            long[] ids = new long[capacity.length];
            for (int i = front, j = 0; i != rear; i++, j++) {
                ids[j] = getMessageId(i);
            }

            byte[][] strongValidators = new byte[capacity.length][];
            byte[][] weakValidators = new byte[capacity.length][];
            for (int i = front, j = 0; i != rear; i++, j++) {
                strongValidators[j] = draft(i) ? null : "S-" + getMessageId(i).getBytes();
                weakValidators[j] = draft(i) ? "W-" + getMessageId(i).getBytes() : null;
            }

            return (long) capacity.length << 32 | occupancy();
        }
    }

    private int getMessageId(int index) {
        return (int) messageIndexToId(index);
    }

    private long messageIndexToId(int index) {
        return front <= index ? (front + capacity.length - index) : (rear + capacity.length - index);
    }

    private int idToIndex(long messageId) {
        return (int) ((messageId - front + capacity.length) % capacity.length);
    }

    private boolean draft(int index) {
        return (front <= index && index < rear) ? front <= index : front > index;
    }

    private int occupancy() {
        return (front <= rear) ? rear - front : capacity.length + rear - front;
    }

    private int occupiedSpace() {
        return (front <= rear) ? capacity.length - (rear - front) : 0;
    }

    private int capacity() {
        return capacity.length;
    }
}

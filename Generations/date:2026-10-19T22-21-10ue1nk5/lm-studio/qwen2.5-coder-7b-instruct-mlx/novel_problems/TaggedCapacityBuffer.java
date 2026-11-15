@TaggedCapacityBuffer.ThreadSafe
class TaggedCapacityBuffer {
    @interface ThreadSafe {}

    private final byte[] buffer;
    private int head = 0, tail = 0, occupancy = 0;
    private final Object lock = new Object();
    private boolean closed = false;

    public TaggedCapacityBuffer(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        buffer = new byte[capacity];
    }

    public long offer(byte[] payload, String[] tags, String[] evictionTags) {
        synchronized (lock) {
            if (isClosed()) throw new IllegalStateException("Buffer is closed");

            while (true) {
                int evictStart = tail;
                for (; evictionStart < head; evictionStart++) {
                    byte[] evictPayload = getPayload(evictStart);
                    if (shouldEvict(evictPayload, evictionTags)) {
                        evict(evictStart);
                    } else break;
                }

                if (occupancy + payload.length <= buffer.length) {
                    insert(payload, tags);
                    return getID(tail - 1); // Return ID of the newly inserted message
                } else {
                    tail = evictStart; // Continue trying to evict more messages
                }
            }
        }
    }

    public void putBlocking(byte[] payload, String[] tags) throws InterruptedException {
        synchronized (lock) {
            while (isClosed()) {
                lock.wait();
            }

            while (!canInsert(payload.length)) {
                lock.wait();
            }

            insert(payload, tags);
        }
    }

    public byte[] takeMatching(String tag) throws InterruptedException {
        synchronized (lock) {
            while (isClosed() && !canTake(tag)) {
                lock.wait();
            }

            while (!canTake(tag)) {
                lock.notifyAll(); // Notify all waiting threads that the buffer is not empty
                lock.wait();
            }

            return remove(tag);
        }
    }

    public boolean moveTo(TaggedCapacityBuffer destination, String tag) throws InterruptedException {
        if (destination == this) throw new IllegalArgumentException("Destination cannot be the same buffer");

        synchronized (lock) {
            while (!canMove(tag)) {
                lock.wait();
            }

            synchronized (destination.lock) {
                while (isClosed() || destination.isFull()) {
                    lock.notifyAll();
                    lock.wait();
                }

                byte[] message = remove(tag);
                if (message != null) {
                    destination.insert(message, new String[0]);
                    return true;
                } else {
                    return false; // No matching message found
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

    public TaggedCapacityBuffer snapshot() {
        synchronized (lock) {
            return new TaggedCapacityBuffer(0) { // Return a snapshot of the current state
                {
                    this.head = TaggedCapacityBuffer.this.head;
                    this.tail = TaggedCapacityBuffer.this.tail;
                    this.occupancy = TaggedCapacityBuffer.this.occupancy;
                    this.buffer = cloneBuffer(TaggedCapacityBuffer.this.buffer);
                }

                @Override
                public byte[] takeMatching(String tag) {
                    return null; // Snapshot does not allow taking messages
                }

                @Override
                public boolean moveTo(TaggedCapacityBuffer destination, String tag) {
                    return false; // Snapshot does not allow moving messages
                }

                @Override
                public void close() {
                    // Do nothing, snapshot is immutable
                }

                @Override
                public TaggedCapacityBuffer snapshot() {
                    return this; // Return a reference to the snapshot itself
                }

                private byte[] cloneBuffer(byte[] original) {
                    byte[] copy = new byte[original.length];
                    System.arraycopy(original, 0, copy, 0, original.length);
                    return copy;
                }
            };
        }
    }

    private boolean isClosed() {
        return closed;
    }

    private boolean canInsert(int length) {
        return !isClosed() && occupancy + length <= buffer.length;
    }

    private boolean canTake(String tag) {
        return head != tail && getTags(tail).contains(tag);
    }

    private boolean canMove(String tag) {
        return head != tail && getTags(tail).contains(tag);
    }

    private byte[] getPayload(int index) {
        return new byte[0]; // Placeholder, not implemented
    }

    private boolean shouldEvict(byte[] payload, String[] evictionTags) {
        return false; // Placeholder, not implemented
    }

    private void evict(int index) {
        System.arraycopy(buffer, index * buffer.length, buffer, (index + 1) * buffer.length, buffer.length - index * buffer.length);
        head++;
        occupancy -= getPayload(index).length;
    }

    private void insert(byte[] payload, String[] tags) {
        System.arraycopy(payload, 0, buffer, tail * buffer.length, payload.length);
        tail++;
        occupancy += payload.length;
    }

    private byte[] remove(String tag) {
        if (head == tail || !getTags(head).contains(tag)) return null;

        byte[] message = getPayload(head);
        head++;
        occupancy -= message.length;
        return message;
    }

    private String[] getTags(int index) {
        return new String[0]; // Placeholder, not implemented
    }

    private long getID(int index) {
        return 0; // Placeholder, not implemented
    }

    private boolean isFull() {
        return occupancy == buffer.length;
    }
}

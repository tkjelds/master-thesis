@TaggedCapacityBuffer.ThreadSafe
class TaggedCapacityBuffer {
    @interface ThreadSafe {}

    private final int capacity;
    private volatile boolean closed;
    private final Message[] messages;
    private int head, tail, size;

    public TaggedCapacityBuffer(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = capacity;
        messages = new Message[capacity];
    }

    public int offer(byte[] payload, String[] tags, String[] evictionTags) {
        if (isClosed()) throw new IllegalStateException("Buffer is closed");
        synchronized (this) {
            while (size == capacity) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Thread interrupted", e);
                }
            }
            int id = generateId();
            messages[tail] = new Message(id, payload, tags);
            tail = (tail + 1) % capacity;
            size++;
            notifyAll();
            return id;
        }
    }

    public synchronized void putBlocking(byte[] payload, String[] tags) {
        while (size == capacity) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Thread interrupted", e);
            }
        }
        int id = generateId();
        messages[tail] = new Message(id, payload, tags);
        tail = (tail + 1) % capacity;
        size++;
        notifyAll();
    }

    public byte[] takeMatching(String tag) throws InterruptedException {
        if (isClosed()) return null;
        synchronized (this) {
            while (size == 0 || !anyMessageHasTag(tag)) {
                wait();
            }
            while (size > 0 && !messages[head].matches(tag)) {
                head = (head + 1) % capacity;
                --size;
            }
            Message message = messages[head];
            head = (head + 1) % capacity;
            --size;
            notifyAll();
            return message.payload;
        }
    }

    public boolean moveTo(TaggedCapacityBuffer destination, String tag) throws InterruptedException {
        if (isClosed() || destination.isClosed()) return false;
        if (this == destination) throw new IllegalArgumentException("Cannot move to the same buffer");
        synchronized (this) {
            while (size == 0 || !anyMessageHasTag(tag)) {
                wait();
            }
            while (size > 0 && !messages[head].matches(tag)) {
                head = (head + 1) % capacity;
                --size;
            }
            Message message = messages[head];
            head = (head + 1) % capacity;
            --size;
            notifyAll();
        }
        synchronized (destination) {
            while (destination.size == destination.capacity) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Thread interrupted", e);
                }
            }
            destination.messages[destination.tail] = message;
            destination.tail = (destination.tail + 1) % destination.capacity;
            destination.size++;
            notifyAll();
        }
        return true;
    }

    public synchronized void close() {
        closed = true;
        notifyAll();
    }

    public Snapshot snapshot() {
        synchronized (this) {
            return new Snapshot(messages, head, tail, size, closed);
        }
    }

    private boolean isClosed() {
        return closed;
    }

    private int generateId() {
        // Simple ID generation logic, may need to be replaced with a more sophisticated method
        return System.identityHashCode(this) + Thread.currentThread().hashCode();
    }

    private boolean anyMessageHasTag(String tag) {
        for (int i = head; i != tail; i = (i + 1) % capacity) {
            if (messages[i].matches(tag)) return true;
        }
        return false;
    }

    private static class Message {
        final int id;
        final byte[] payload;
        final String[] tags;

        public Message(int id, byte[] payload, String[] tags) {
            this.id = id;
            this.payload = payload;
            this.tags = tags;
        }

        boolean matches(String tag) {
            for (String t : tags) {
                if (t.equals(tag)) return true;
            }
            return false;
        }
    }

    static class Snapshot {
        private final Message[] messages;
        private final int head, tail, size;
        private final boolean closed;

        public Snapshot(Message[] messages, int head, int tail, int size, boolean closed) {
            this.messages = messages;
            this.head = head;
            this.tail = tail;
            this.size = size;
            this.closed = closed;
        }

        public Message[] getMessages() {
            return messages;
        }

        public int getSize() {
            return size;
        }

        public boolean isClosed() {
            return closed;
        }
    }
}

@TaggedCapacityBuffer.ThreadSafe
public class TaggedCapacityBuffer {
    @interface ThreadSafe {}

    private final int capacity;
    private final Object lock;
    private boolean closed;
    private long nextId;
    private Node head;
    private Node tail;
    private int occupancy;

    public TaggedCapacityBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.lock = new Object();
        this.nextId = 1L;
    }

    public int getCapacity() {
        synchronized (lock) {
            return capacity;
        }
    }

    public int getOccupancy() {
        synchronized (lock) {
            return occupancy;
        }
    }

    public boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    public long offer(byte[] payload, String[] tags, String[] evictionTags) {
        validatePayload(payload);
        validateTags(tags);
        if (evictionTags == null) {
            throw new IllegalArgumentException("evictionTags is null");
        }
        final byte[] payloadCopy = copyBytes(payload);
        final String[] tagCopy = copyStrings(tags);
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("buffer is closed");
            }
            if (payloadCopy.length > capacity) {
                throw new IllegalArgumentException("payload exceeds capacity");
            }
            while (occupancy + payloadCopy.length > capacity) {
                if (!evictForOffer(evictionTags)) {
                    return -1L;
                }
            }
            final long id = nextId++;
            insert(new Message(id, payloadCopy, tagCopy));
            lock.notifyAll();
            return id;
        }
    }

    public long putBlocking(byte[] payload, String[] tags) throws InterruptedException {
        validatePayload(payload);
        validateTags(tags);
        final byte[] payloadCopy = copyBytes(payload);
        final String[] tagCopy = copyStrings(tags);
        synchronized (lock) {
            while (true) {
                if (closed) {
                    throw new IllegalStateException("buffer is closed");
                }
                if (occupancy + payloadCopy.length <= capacity) {
                    final long id = nextId++;
                    insert(new Message(id, payloadCopy, tagCopy));
                    lock.notifyAll();
                    return id;
                }
                lock.wait();
            }
        }
    }

    public Message takeMatching(String tag) throws InterruptedException {
        if (tag == null) {
            throw new IllegalArgumentException("tag is null");
        }
        synchronized (lock) {
            while (true) {
                final Node node = findOldestMatchingNode(tag);
                if (node != null) {
                    remove(node);
                    lock.notifyAll();
                    return new Message(node.message.id, node.message.payload, node.message.tags);
                }
                if (closed) {
                    return null;
                }
                lock.wait();
            }
        }
    }

    public boolean moveTo(TaggedCapacityBuffer destination, String tag) {
        if (destination == null) {
            throw new IllegalArgumentException("destination is null");
        }
        if (tag == null) {
            throw new IllegalArgumentException("tag is null");
        }
        if (destination == this) {
            throw new IllegalArgumentException("destination must differ from source");
        }

        TaggedCapacityBuffer first;
        TaggedCapacityBuffer second;
        if (System.identityHashCode(this) < System.identityHashCode(destination)) {
            first = this;
            second = destination;
        } else {
            first = destination;
            second = this;
        }

        synchronized (first.lock) {
            synchronized (second.lock) {
                if (closed || destination.closed) {
                    return false;
                }

                final Node node = findOldestMatchingNode(tag);
                if (node == null) {
                    return false;
                }
                if (destination.occupancy + node.message.payload.length > destination.capacity) {
                    return false;
                }

                remove(node);
                final Message moved = new Message(node.message.id, node.message.payload, node.message.tags);
                destination.insert(moved);

                first.lock.notifyAll();
                second.lock.notifyAll();
                return true;
            }
        }
    }

    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            lock.notifyAll();
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            final Message[] copy = new Message[size()];
            int index = 0;
            Node current = head;
            while (current != null) {
                copy[index] = new Message(current.message.id, current.message.payload, current.message.tags);
                index++;
                current = current.next;
            }
            return new Snapshot(copy, occupancy, capacity, closed);
        }
    }

    private int size() {
        int count = 0;
        Node current = head;
        while (current != null) {
            count++;
            current = current.next;
        }
        return count;
    }

    private boolean evictForOffer(String[] evictionTags) {
        final int index = findOldestEvictionCandidateIndex(evictionTags);
        if (index >= 0) {
            removeAt(index);
            return true;
        }
        if (head == null) {
            return false;
        }
        removeAt(0);
        return true;
    }

    private int findOldestEvictionCandidateIndex(String[] evictionTags) {
        Node current = head;
        int index = 0;
        while (current != null) {
            if (sharesAnyTag(current.message.tags, evictionTags)) {
                return index;
            }
            current = current.next;
            index++;
        }
        return -1;
    }

    private Node findOldestMatchingNode(String tag) {
        Node current = head;
        while (current != null) {
            if (containsTag(current.message.tags, tag)) {
                return current;
            }
            current = current.next;
        }
        return null;
    }

    private void insert(Message message) {
        final Node node = new Node(message);
        if (head == null) {
            head = node;
            tail = node;
        } else {
            tail.next = node;
            node.prev = tail;
            tail = node;
        }
        occupancy += message.payload.length;
    }

    private void remove(Node node) {
        if (node == null) {
            return;
        }
        if (node.prev != null) {
            node.prev.next = node.next;
        } else {
            head = node.next;
        }
        if (node.next != null) {
            node.next.prev = node.prev;
        } else {
            tail = node.prev;
        }

        occupancy -= node.message.payload.length;

        node.prev = null;
        node.next = null;
    }

    private void removeAt(int index) {
        Node current = head;
        int pos = 0;
        while (current != null && pos < index) {
            current = current.next;
            pos++;
        }
        remove(current);
    }

    private static void validatePayload(byte[] payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload is null");
        }
        if (payload.length == 0) {
            throw new IllegalArgumentException("payload is empty");
        }
    }

    private static void validateTags(String[] tags) {
        if (tags == null) {
            throw new IllegalArgumentException("tags is null");
        }
        for (int i = 0; i < tags.length; i++) {
            if (tags[i] == null) {
                throw new IllegalArgumentException("tag is null");
            }
        }
    }

    private static byte[] copyBytes(byte[] source) {
        final byte[] copy = new byte[source.length];
        System.arraycopy(source, 0, copy, 0, source.length);
        return copy;
    }

    private static String[] copyStrings(String[] source) {
        final String[] copy = new String[source.length];
        System.arraycopy(source, 0, copy, 0, source.length);
        return copy;
    }

    private static boolean containsTag(String[] tags, String tag) {
        for (int i = 0; i < tags.length; i++) {
            if (tags[i].equals(tag)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sharesAnyTag(String[] messageTags, String[] filterTags) {
        for (int i = 0; i < messageTags.length; i++) {
            for (int j = 0; j < filterTags.length; j++) {
                if (messageTags[i].equals(filterTags[j])) {
                    return true;
                }
            }
        }
        return false;
    }

    public static final class Message {
        public final long id;
        public final byte[] payload;
        public final String[] tags;

        public Message(long id, byte[] payload, String[] tags) {
            if (payload == null) {
                throw new IllegalArgumentException("payload is null");
            }
            if (tags == null) {
                throw new IllegalArgumentException("tags is null");
            }
            this.id = id;
            this.payload = copyBytes(payload);
            this.tags = copyStrings(tags);
        }

        public long getId() {
            return id;
        }

        public byte[] getPayload() {
            return copyBytes(payload);
        }

        public String[] getTags() {
            return copyStrings(tags);
        }
    }

    public static final class Snapshot {
        public final Message[] messages;
        public final int occupancy;
        public final int capacity;
        public final boolean closed;

        public Snapshot(Message[] messages, int occupancy, int capacity, boolean closed) {
            this.messages = messages;
            this.occupancy = occupancy;
            this.capacity = capacity;
            this.closed = closed;
        }

        public Message[] getMessages() {
            return messages == null ? null : messages.clone();
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

    private static final class Node {
        private final Message message;
        private Node prev;
        private Node next;

        private Node(Message message) {
            this.message = message;
        }
    }
}

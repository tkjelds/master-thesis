@TaggedCapacityBuffer.ThreadSafe
public class TaggedCapacityBuffer {
    @interface ThreadSafe {}

    private static final int MAX_ID = Integer.MAX_VALUE;
    private static volatile int nextId = 0;

    private final byte[] buffer;
    private final int capacity;
    private volatile MessageNode head;
    private volatile MessageNode tail;
    private volatile boolean closed = false;

    // Inner class representing a message node
    private static class MessageNode {
        final int id;
        final byte[] payload;
        final String[] tags;
        MessageNode next;

        MessageNode(int id, byte[] payload, String[] tags) {
            this.id = id;
            this.payload = payload.clone();
            this.tags = tags != null ? tags.clone() : new String[0];
            this.next = null;
        }
    }

    public TaggedCapacityBuffer(int byteCapacity) {
        if (byteCapacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = byteCapacity;
        this.buffer = new byte[0]; // Buffer is not directly used; messages are stored in linked list
    }

    private int getOccupancy() {
        int occupancy = 0;
        MessageNode current = head;
        while (current != null) {
            occupancy += current.payload.length;
            current = current.next;
        }
        return occupancy;
    }

    private void evictOldestWithTag(String[] evictionTags) {
        if (evictionTags == null || evictionTags.length == 0) return;

        MessageNode prev = null;
        MessageNode current = head;
        while (current != null) {
            boolean shouldEvict = false;
            for (String tag : current.tags) {
                if (evictionTags != null && contains(evictionTags, tag)) {
                    shouldEvict = true;
                    break;
                }
            }

            if (shouldEvict) {
                int evictedLength = current.payload.length;
                MessageNode toRemove = current;

                if (prev == null) {
                    head = current.next;
                } else {
                    prev.next = current.next;
                }

                if (tail == toRemove) {
                    tail = prev;
                }

                current = current.next; // Move to next before removing
                continue;
            } else {
                prev = current;
                current = current.next;
            }
        }
    }

    private void evictOldestMessages(int bytesToFree) {
        if (bytesToFree <= 0 || head == null) return;

        MessageNode prev = null;
        MessageNode current = head;
        int freedBytes = 0;

        while (current != null && freedBytes < bytesToFree) {
            int payloadLength = current.payload.length;
            if (payloadLength <= freedBytes) {
                MessageNode toRemove = current;

                if (prev == null) {
                    head = current.next;
                } else {
                    prev.next = current.next;
                }

                if (tail == toRemove) {
                    tail = prev;
                }

                freedBytes += payloadLength;
                current = current.next; // Move to next before removing
            } else {
                prev = current;
                current = current.next;
            }
        }
    }

    private boolean contains(String[] array, String element) {
        for (String s : array) {
            if (s != null && s.equals(element)) {
                return true;
            }
        }
        return false;
    }

    private synchronized int offerInternal(byte[] payload, String[] tags) {
        if (payload == null || payload.length == 0 || tags == null) {
            throw new IllegalArgumentException("Payload and tags must be non-null and payload cannot be empty");
        }

        if (payload.length > capacity) {
            throw new IllegalArgumentException("Payload exceeds buffer capacity");
        }

        int requiredCapacity = payload.length;
        MessageNode newNode = new MessageNode(nextId++, payload, tags);

        // Check if eviction is needed
        int currentOccupancy = getOccupancy();
        if (currentOccupancy + requiredCapacity > capacity) {
            // Evict oldest messages with eviction tags first
            if (tags != null && tags.length > 0) {
                String[] evictionTags = tags;
                evictOldestWithTag(evictionTags);
            }

            // If still not enough space, evict oldest messages
            int newOccupancy = getOccupancy();
            if (newOccupancy + requiredCapacity > capacity) {
                evictOldestMessages(newOccupancy + requiredCapacity - capacity);
            }
        }

        // Add the new message
        if (tail == null) {
            head = tail = newNode;
        } else {
            tail.next = newNode;
            tail = newNode;
        }

        return newNode.id;
    }

    public int offer(byte[] payload, String[] tags) {
        synchronized (this) {
            return offerInternal(payload, tags);
        }
    }

    public void putBlocking(byte[] payload, String[] tags) throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }

        while (true) {
            synchronized (this) {
                if (closed) {
                    throw new IllegalStateException("Buffer is closed");
                }

                int requiredCapacity = payload.length;
                int currentOccupancy = getOccupancy();

                if (currentOccupancy + requiredCapacity <= capacity) {
                    offerInternal(payload, tags);
                    return;
                } else {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        }
    }

    public MessageNode takeMatching(String tag) throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }

        while (true) {
            synchronized (this) {
                if (closed && head == null) {
                    return null;
                }

                MessageNode current = head;
                MessageNode prev = null;

                while (current != null) {
                    boolean matchesTag = false;
                    for (String ctag : current.tags) {
                        if (ctag != null && ctag.equals(tag)) {
                            matchesTag = true;
                            break;
                        }
                    }

                    if (matchesTag) {
                        MessageNode toRemove = current;

                        if (prev == null) {
                            head = current.next;
                        } else {
                            prev.next = current.next;
                        }

                        if (tail == toRemove) {
                            tail = prev;
                        }

                        return new MessageNode(toRemove.id, toRemove.payload.clone(), toRemove.tags.clone());
                    } else {
                        prev = current;
                        current = current.next;
                    }
                }

                if (closed) {
                    return null;
                } else {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        }
    }

    public boolean moveTo(TaggedCapacityBuffer destination, String tag) {
        if (destination == this || closed || destination.closed) {
            return false;
        }

        synchronized (this) {
            if (head == null || tail == null) return false;

            MessageNode current = head;
            MessageNode prev = null;

            while (current != null) {
                boolean matchesTag = false;
                for (String ctag : current.tags) {
                    if (ctag != null && ctag.equals(tag)) {
                        matchesTag = true;
                        break;
                    }
                }

                if (matchesTag) {
                    MessageNode toMove = current;

                    // Remove from source
                    if (prev == null) {
                        head = current.next;
                    } else {
                        prev.next = current.next;
                    }

                    if (tail == toMove) {
                        tail = prev;
                    }

                    // Add to destination
                    synchronized (destination) {
                        if (destination.head == null) {
                            destination.head = toMove;
                            destination.tail = toMove;
                        } else {
                            destination.tail.next = toMove;
                            destination.tail = toMove;
                        }
                    }

                    return true;
                } else {
                    prev = current;
                    current = current.next;
                }
            }

            return false;
        }
    }

    public void close() {
        synchronized (this) {
            closed = true;
            notifyAll();
        }
    }

    public MessageNode[] snapshot() {
        synchronized (this) {
            if (head == null) return new MessageNode[0];

            int size = 0;
            MessageNode current = head;

            while (current != null) {
                size++;
                current = current.next;
            }

            MessageNode[] snapshotArray = new MessageNode[size];
            int index = 0;
            current = head;

            while (current != null) {
                snapshotArray[index] = new MessageNode(current.id, current.payload.clone(), current.tags.clone());
                index++;
                current = current.next;
            }

            return snapshotArray;
        }
    }

    public int getOccupancy() {
        synchronized (this) {
            return getOccupancy();
        }
    }

    public int getCapacity() {
        return capacity;
    }

    public boolean isClosed() {
        return closed;
    }
}

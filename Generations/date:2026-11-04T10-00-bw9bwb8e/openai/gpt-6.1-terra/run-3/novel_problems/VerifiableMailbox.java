@VerifiableMailbox.ThreadSafe
public final class VerifiableMailbox {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final int capacity;

    private int occupancy;
    private int messageCount;
    private int strongCount;
    private int weakCount;
    private long nextId = 1;

    private Node head;
    private Node tail;
    private Node strongHead;
    private Node strongTail;
    private Node weakHead;
    private Node weakTail;

    public VerifiableMailbox(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
    }

    /**
     * Never waits for capacity: oldest messages are evicted as needed.
     */
    public long receive(byte[] content, boolean draft) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("Content must not be null or empty");
        }
        if (content.length > capacity) {
            throw new IllegalArgumentException("Content exceeds capacity");
        }

        byte[] copy = content.clone();

        synchronized (lock) {
            for (Node current = head; current != null; current = current.next) {
                if (sameContent(current.content, copy)) {
                    return -1;
                }
            }

            if (nextId == 0) {
                throw new IllegalStateException("Message IDs exhausted");
            }

            long id = nextId;
            String validator = (draft ? "W-" : "S-") + id;
            Node node = new Node(id, copy, draft, validator);

            while (copy.length > capacity - occupancy) {
                removeOldest();
            }

            if (tail == null) {
                head = node;
            } else {
                tail.next = node;
            }
            tail = node;

            if (draft) {
                if (weakTail == null) {
                    weakHead = node;
                } else {
                    weakTail.nextValidator = node;
                }
                weakTail = node;
                weakCount++;
            } else {
                if (strongTail == null) {
                    strongHead = node;
                } else {
                    strongTail.nextValidator = node;
                }
                strongTail = node;
                strongCount++;
            }

            occupancy += copy.length;
            messageCount++;
            nextId = id == Long.MAX_VALUE ? 0 : id + 1;
            return id;
        }
    }

    public Message fetchIfMatch(long messageId, String validator) {
        if (messageId <= 0) {
            throw new IllegalArgumentException("Message ID must be positive");
        }
        if (validator == null) {
            throw new IllegalArgumentException("Validator must not be null");
        }

        synchronized (lock) {
            for (Node current = head; current != null; current = current.next) {
                if (current.id == messageId) {
                    if (!current.validator.equals(validator)) {
                        return null;
                    }
                    return new Message(
                            current.id,
                            current.content.clone(),
                            current.draft,
                            current.validator);
                }
                if (current.id > messageId) {
                    break;
                }
            }
            return null;
        }
    }

    public int prune(long throughId) {
        if (throughId < 0) {
            throw new IllegalArgumentException("throughId must be nonnegative");
        }

        synchronized (lock) {
            int removed = 0;
            while (head != null && head.id <= throughId) {
                removeOldest();
                removed++;
            }
            return removed;
        }
    }

    public SnapshotHeader snapshotHeader() {
        synchronized (lock) {
            long[] ids = new long[messageCount];
            String[] strongValidators = new String[strongCount];
            String[] weakValidators = new String[weakCount];

            int index = 0;
            for (Node current = head; current != null; current = current.next) {
                ids[index++] = current.id;
            }

            index = 0;
            for (Node current = strongHead;
                 current != null;
                 current = current.nextValidator) {
                strongValidators[index++] = current.validator;
            }

            index = 0;
            for (Node current = weakHead;
                 current != null;
                 current = current.nextValidator) {
                weakValidators[index++] = current.validator;
            }

            return new SnapshotHeader(
                    capacity, occupancy, ids, strongValidators, weakValidators);
        }
    }

    private static boolean sameContent(byte[] first, byte[] second) {
        if (first.length != second.length) {
            return false;
        }
        for (int i = 0; i < first.length; i++) {
            if (first[i] != second[i]) {
                return false;
            }
        }
        return true;
    }

    // Called only with lock held and a nonempty inbox.
    private void removeOldest() {
        Node removed = head;
        head = removed.next;
        if (head == null) {
            tail = null;
        }

        if (removed.draft) {
            weakHead = removed.nextValidator;
            if (weakHead == null) {
                weakTail = null;
            }
            weakCount--;
        } else {
            strongHead = removed.nextValidator;
            if (strongHead == null) {
                strongTail = null;
            }
            strongCount--;
        }

        occupancy -= removed.content.length;
        messageCount--;
        removed.next = null;
        removed.nextValidator = null;
    }

    private static final class Node {
        final long id;
        final byte[] content;
        final boolean draft;
        final String validator;
        Node next;
        Node nextValidator;

        Node(long id, byte[] content, boolean draft, String validator) {
            this.id = id;
            this.content = content;
            this.draft = draft;
            this.validator = validator;
        }
    }

    public static final class Message {
        private final long messageId;
        private final byte[] content;
        private final boolean draft;
        private final String validator;

        private Message(long messageId, byte[] content, boolean draft, String validator) {
            this.messageId = messageId;
            this.content = content;
            this.draft = draft;
            this.validator = validator;
        }

        public long getMessageId() {
            return messageId;
        }

        public byte[] getContent() {
            return content.clone();
        }

        public boolean isDraft() {
            return draft;
        }

        public String getValidator() {
            return validator;
        }
    }

    public static final class SnapshotHeader {
        private final int capacity;
        private final int occupancy;
        private final long[] messageIds;
        private final String[] strongValidators;
        private final String[] weakValidators;

        private SnapshotHeader(
                int capacity,
                int occupancy,
                long[] messageIds,
                String[] strongValidators,
                String[] weakValidators) {
            this.capacity = capacity;
            this.occupancy = occupancy;
            this.messageIds = messageIds;
            this.strongValidators = strongValidators;
            this.weakValidators = weakValidators;
        }

        public int getCapacity() {
            return capacity;
        }

        public int getOccupancy() {
            return occupancy;
        }

        public long[] getMessageIds() {
            return messageIds.clone();
        }

        public String[] getStrongValidators() {
            return strongValidators.clone();
        }

        public String[] getWeakValidators() {
            return weakValidators.clone();
        }
    }
}

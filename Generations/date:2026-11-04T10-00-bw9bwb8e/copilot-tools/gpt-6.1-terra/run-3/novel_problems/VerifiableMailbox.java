@VerifiableMailbox.ThreadSafe
public final class VerifiableMailbox {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final int capacity;

    private int occupancy;
    private int messageCount;
    private int weakCount;
    private long nextId = 1;
    private boolean idExhausted;

    private Node oldest;
    private Node newest;
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

    // Operations do not wait for space; receive evicts the oldest messages.
    public long receive(byte[] content, boolean draft) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("Content must not be null or empty");
        }
        if (content.length > capacity) {
            throw new IllegalArgumentException("Content exceeds capacity");
        }

        byte[] retained = copyBytes(content);
        synchronized (lock) {
            for (Node node = oldest; node != null; node = node.next) {
                if (sameBytes(node.content, retained)) {
                    return -1;
                }
            }

            if (idExhausted) {
                throw new IllegalStateException("Message IDs are exhausted");
            }

            long id = nextId;
            Node added = new Node(id, retained, draft);

            while (retained.length > capacity - occupancy) {
                removeOldest();
            }

            if (newest == null) {
                oldest = added;
            } else {
                newest.next = added;
            }
            newest = added;

            if (draft) {
                if (weakTail == null) {
                    weakHead = added;
                } else {
                    weakTail.validatorNext = added;
                }
                weakTail = added;
                weakCount++;
            } else {
                if (strongTail == null) {
                    strongHead = added;
                } else {
                    strongTail.validatorNext = added;
                }
                strongTail = added;
            }

            occupancy += retained.length;
            messageCount++;
            if (id == Long.MAX_VALUE) {
                idExhausted = true;
            } else {
                nextId = id + 1;
            }
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
            for (Node node = oldest; node != null; node = node.next) {
                if (node.id == messageId) {
                    if (node.validator.equals(validator)) {
                        return new Message(node);
                    }
                    return null;
                }
                if (node.id > messageId) {
                    return null;
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
            while (oldest != null && oldest.id <= throughId) {
                removeOldest();
                removed++;
            }
            return removed;
        }
    }

    public Header snapshotHeader() {
        synchronized (lock) {
            long[] ids = new long[messageCount];
            String[] strongValidators = new String[messageCount - weakCount];
            String[] weakValidators = new String[weakCount];

            int index = 0;
            for (Node node = oldest; node != null; node = node.next) {
                ids[index++] = node.id;
            }
            index = 0;
            for (Node node = strongHead; node != null; node = node.validatorNext) {
                strongValidators[index++] = node.validator;
            }
            index = 0;
            for (Node node = weakHead; node != null; node = node.validatorNext) {
                weakValidators[index++] = node.validator;
            }

            return new Header(capacity, occupancy, ids, strongValidators, weakValidators);
        }
    }

    private void removeOldest() {
        Node removed = oldest;
        oldest = removed.next;
        if (oldest == null) {
            newest = null;
        }

        if (removed.draft) {
            weakHead = removed.validatorNext;
            if (weakHead == null) {
                weakTail = null;
            }
            weakCount--;
        } else {
            strongHead = removed.validatorNext;
            if (strongHead == null) {
                strongTail = null;
            }
        }

        occupancy -= removed.content.length;
        messageCount--;
    }

    private static boolean sameBytes(byte[] left, byte[] right) {
        if (left.length != right.length) {
            return false;
        }
        for (int i = 0; i < left.length; i++) {
            if (left[i] != right[i]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] copyBytes(byte[] source) {
        byte[] copy = new byte[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }

    private static long[] copyLongs(long[] source) {
        long[] copy = new long[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }

    private static String[] copyStrings(String[] source) {
        String[] copy = new String[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }

    private static final class Node {
        private final long id;
        private final byte[] content;
        private final boolean draft;
        private final String validator;
        private Node next;
        private Node validatorNext;

        private Node(long id, byte[] content, boolean draft) {
            this.id = id;
            this.content = content;
            this.draft = draft;
            this.validator = (draft ? "W-" : "S-") + id;
        }
    }

    public static final class Message {
        private final long id;
        private final byte[] content;
        private final boolean draft;
        private final String validator;

        private Message(Node node) {
            this.id = node.id;
            this.content = copyBytes(node.content);
            this.draft = node.draft;
            this.validator = node.validator;
        }

        public long getId() {
            return id;
        }

        public byte[] getContent() {
            return copyBytes(content);
        }

        public boolean isDraft() {
            return draft;
        }

        public String getValidator() {
            return validator;
        }
    }

    public static final class Header {
        private final int capacity;
        private final int occupancy;
        private final long[] messageIds;
        private final String[] strongValidators;
        private final String[] weakValidators;

        private Header(int capacity, int occupancy, long[] messageIds,
                       String[] strongValidators, String[] weakValidators) {
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
            return copyLongs(messageIds);
        }

        public String[] getStrongValidators() {
            return copyStrings(strongValidators);
        }

        public String[] getWeakValidators() {
            return copyStrings(weakValidators);
        }
    }
}

/**
 * A byte-bounded FIFO mailbox. Operations never wait for capacity:
 * receive evicts oldest messages, and an unsuccessful fetch returns null.
 * All mailbox state is protected by one intrinsic monitor.
 */
@VerifiableMailbox.ThreadSafe
public final class VerifiableMailbox {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final int capacity;

    private int occupancy;
    private int size;
    private int strongCount;
    private int weakCount;
    private long lastAssignedId;

    private Entry head;
    private Entry tail;
    private Entry strongHead;
    private Entry strongTail;
    private Entry weakHead;
    private Entry weakTail;

    private static final class Entry {
        final long messageId;
        final byte[] content;
        final boolean draft;
        final String validator;
        Entry next;
        Entry nextValidator;

        Entry(long messageId, byte[] content, boolean draft) {
            this.messageId = messageId;
            this.content = content;
            this.draft = draft;
            this.validator = (draft ? "W-" : "S-") + messageId;
        }
    }

    /**
     * A detached message. Its content array belongs to the caller.
     */
    public static final class Message {
        public final long messageId;
        public final byte[] content;
        public final boolean draft;
        public final String validator;

        private Message(Entry entry) {
            this.messageId = entry.messageId;
            this.content = copyBytes(entry.content);
            this.draft = entry.draft;
            this.validator = entry.validator;
        }
    }

    /**
     * A coherent, detached snapshot. All arrays belong to the caller.
     * Message IDs and each validator array are ordered by insertion.
     */
    public static final class Header {
        public final int capacity;
        public final int occupancy;
        public final long[] messageIds;
        public final String[] strongValidators;
        public final String[] weakValidators;

        private Header(int capacity, int occupancy, long[] messageIds,
                       String[] strongValidators, String[] weakValidators) {
            this.capacity = capacity;
            this.occupancy = occupancy;
            this.messageIds = messageIds;
            this.strongValidators = strongValidators;
            this.weakValidators = weakValidators;
        }
    }

    public VerifiableMailbox(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
    }

    /**
     * Returns -1 for content already present, or a new positive message ID.
     *
     * @throws IllegalArgumentException for null, empty, or oversized content
     * @throws IllegalStateException if a new message requires an exhausted ID
     */
    public long receive(byte[] content, boolean draft) {
        if (content == null) {
            throw new IllegalArgumentException("Content must not be null");
        }
        if (content.length == 0) {
            throw new IllegalArgumentException("Content must not be empty");
        }
        if (content.length > capacity) {
            throw new IllegalArgumentException("Content exceeds capacity");
        }

        byte[] retainedContent = copyBytes(content);

        synchronized (lock) {
            for (Entry entry = head; entry != null; entry = entry.next) {
                if (sameBytes(entry.content, retainedContent)) {
                    return -1L;
                }
            }

            if (lastAssignedId == 9223372036854775807L) {
                throw new IllegalStateException("Message IDs are exhausted");
            }

            long messageId = lastAssignedId + 1L;
            Entry entry = new Entry(messageId, retainedContent, draft);

            while (retainedContent.length > capacity - occupancy) {
                removeOldest();
            }

            if (tail == null) {
                head = entry;
            } else {
                tail.next = entry;
            }
            tail = entry;

            if (draft) {
                if (weakTail == null) {
                    weakHead = entry;
                } else {
                    weakTail.nextValidator = entry;
                }
                weakTail = entry;
                weakCount++;
            } else {
                if (strongTail == null) {
                    strongHead = entry;
                } else {
                    strongTail.nextValidator = entry;
                }
                strongTail = entry;
                strongCount++;
            }

            occupancy += retainedContent.length;
            size++;
            lastAssignedId = messageId;
            return messageId;
        }
    }

    /**
     * Fetches without consuming. Returns null unless both arguments match.
     */
    public Message fetchIfMatch(long messageId, String validator) {
        if (messageId <= 0L) {
            throw new IllegalArgumentException("Message ID must be positive");
        }
        if (validator == null) {
            throw new IllegalArgumentException("Validator must not be null");
        }

        synchronized (lock) {
            for (Entry entry = head; entry != null; entry = entry.next) {
                if (entry.messageId == messageId) {
                    return entry.validator.equals(validator)
                            ? new Message(entry) : null;
                }
                if (entry.messageId > messageId) {
                    break;
                }
            }
            return null;
        }
    }

    public int prune(long throughId) {
        if (throughId < 0L) {
            throw new IllegalArgumentException("Through ID must be nonnegative");
        }

        synchronized (lock) {
            int removed = 0;
            while (head != null && head.messageId <= throughId) {
                removeOldest();
                removed++;
            }
            return removed;
        }
    }

    public Header snapshotHeader() {
        synchronized (lock) {
            long[] messageIds = new long[size];
            String[] strongValidators = new String[strongCount];
            String[] weakValidators = new String[weakCount];

            int index = 0;
            for (Entry entry = head; entry != null; entry = entry.next) {
                messageIds[index++] = entry.messageId;
            }

            index = 0;
            for (Entry entry = strongHead;
                 entry != null; entry = entry.nextValidator) {
                strongValidators[index++] = entry.validator;
            }

            index = 0;
            for (Entry entry = weakHead;
                 entry != null; entry = entry.nextValidator) {
                weakValidators[index++] = entry.validator;
            }

            return new Header(capacity, occupancy, messageIds,
                    strongValidators, weakValidators);
        }
    }

    // Called only with lock held and a nonempty inbox.
    private void removeOldest() {
        Entry removed = head;
        head = removed.next;
        if (head == null) {
            tail = null;
        }

        // The oldest entry is also the oldest member of its validator set.
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
        size--;
        removed.next = null;
        removed.nextValidator = null;
    }

    private static byte[] copyBytes(byte[] source) {
        byte[] copy = new byte[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }

    private static boolean sameBytes(byte[] first, byte[] second) {
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
}

@VerifiableMailbox.ThreadSafe
public final class VerifiableMailbox {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final int capacity;

    private int occupancy;
    private int messageCount;
    private int strongCount;
    private int weakCount;
    private long lastAssignedId;

    private Entry head;
    private Entry tail;
    private Entry strongHead;
    private Entry strongTail;
    private Entry weakHead;
    private Entry weakTail;

    public VerifiableMailbox(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
    }

    // Capacity shortages cause FIFO eviction, never a wait for space.
    public long receive(byte[] content, boolean draft) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("Content must not be null or empty");
        }
        if (content.length > capacity) {
            throw new IllegalArgumentException("Content exceeds capacity");
        }

        byte[] retained = copy(content);

        synchronized (lock) {
            for (Entry entry = head; entry != null; entry = entry.next) {
                if (equalBytes(entry.content, retained)) {
                    return -1L;
                }
            }

            if (lastAssignedId == Long.MAX_VALUE) {
                throw new IllegalStateException("Message IDs exhausted");
            }

            long id = lastAssignedId + 1L;
            Entry added = new Entry(id, retained, draft);

            while (retained.length > capacity - occupancy) {
                removeOldest();
            }

            if (tail == null) {
                head = added;
            } else {
                tail.next = added;
            }
            tail = added;

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
                strongCount++;
            }

            occupancy += retained.length;
            messageCount++;
            lastAssignedId = id;
            return id;
        }
    }

    // Missing messages and validator mismatches return null without waiting.
    public Message fetchIfMatch(long messageId, String validator) {
        if (messageId <= 0L) {
            throw new IllegalArgumentException("Message ID must be positive");
        }
        if (validator == null) {
            throw new IllegalArgumentException("Validator must not be null");
        }

        synchronized (lock) {
            for (Entry entry = head; entry != null; entry = entry.next) {
                if (entry.id > messageId) {
                    break;
                }
                if (entry.id == messageId) {
                    if (!entry.validator.equals(validator)) {
                        return null;
                    }
                    return new Message(entry);
                }
            }
            return null;
        }
    }

    public int prune(long throughId) {
        if (throughId < 0L) {
            throw new IllegalArgumentException("Prune ID must be nonnegative");
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

    public Header snapshotHeader() {
        synchronized (lock) {
            long[] messageIds = new long[messageCount];
            String[] strongValidators = new String[strongCount];
            String[] weakValidators = new String[weakCount];

            int index = 0;
            for (Entry entry = head; entry != null; entry = entry.next) {
                messageIds[index++] = entry.id;
            }

            index = 0;
            for (Entry entry = strongHead;
                 entry != null;
                 entry = entry.validatorNext) {
                strongValidators[index++] = entry.validator;
            }

            index = 0;
            for (Entry entry = weakHead;
                 entry != null;
                 entry = entry.validatorNext) {
                weakValidators[index++] = entry.validator;
            }

            return new Header(
                capacity, occupancy, messageIds, strongValidators, weakValidators
            );
        }
    }

    // Called only with lock held. Each validator list follows inbox order.
    private void removeOldest() {
        Entry removed = head;
        head = removed.next;
        if (head == null) {
            tail = null;
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
            strongCount--;
        }

        occupancy -= removed.content.length;
        messageCount--;
        removed.next = null;
        removed.validatorNext = null;
    }

    private static byte[] copy(byte[] source) {
        byte[] result = new byte[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i];
        }
        return result;
    }

    private static boolean equalBytes(byte[] left, byte[] right) {
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

    private static final class Entry {
        final long id;
        final byte[] content;
        final boolean draft;
        final String validator;
        Entry next;
        Entry validatorNext;

        Entry(long id, byte[] content, boolean draft) {
            this.id = id;
            this.content = content;
            this.draft = draft;
            this.validator = (draft ? "W-" : "S-") + id;
        }
    }

    // The payload belongs to the caller; modifying it cannot affect the inbox.
    public static final class Message {
        public final long id;
        public final byte[] content;
        public final boolean draft;
        public final String validator;

        private Message(Entry entry) {
            this.id = entry.id;
            this.content = copy(entry.content);
            this.draft = entry.draft;
            this.validator = entry.validator;
        }
    }

    // Arrays belong to this snapshot and are never retained by the mailbox.
    public static final class Header {
        public final int capacity;
        public final int occupancy;
        public final long[] messageIds;
        public final String[] strongValidators;
        public final String[] weakValidators;

        private Header(
            int capacity,
            int occupancy,
            long[] messageIds,
            String[] strongValidators,
            String[] weakValidators
        ) {
            this.capacity = capacity;
            this.occupancy = occupancy;
            this.messageIds = messageIds;
            this.strongValidators = strongValidators;
            this.weakValidators = weakValidators;
        }
    }
}

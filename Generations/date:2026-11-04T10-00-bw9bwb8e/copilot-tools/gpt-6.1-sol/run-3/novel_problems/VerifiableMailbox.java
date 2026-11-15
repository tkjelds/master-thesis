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

    private Entry first;
    private Entry last;
    private Entry strongFirst;
    private Entry strongLast;
    private Entry weakFirst;
    private Entry weakLast;

    public VerifiableMailbox(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
    }

    /**
     * Never waits for capacity: evicts oldest messages when necessary.
     * Duplicate content returns -1 without changing the mailbox.
     */
    public long receive(byte[] content, boolean draft) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("Content must not be null or empty");
        }
        if (content.length > capacity) {
            throw new IllegalArgumentException("Content exceeds capacity");
        }

        byte[] retainedContent = content.clone();

        synchronized (lock) {
            for (Entry entry = first; entry != null; entry = entry.next) {
                if (sameContent(entry.content, retainedContent)) {
                    return -1L;
                }
            }

            if (lastAssignedId == Long.MAX_VALUE) {
                throw new IllegalStateException("Message IDs exhausted");
            }

            long id = lastAssignedId + 1L;
            Entry entry = new Entry(id, retainedContent, draft);

            while (occupancy > capacity - retainedContent.length) {
                removeFirst();
            }

            if (last == null) {
                first = entry;
            } else {
                last.next = entry;
            }
            last = entry;

            if (draft) {
                if (weakLast == null) {
                    weakFirst = entry;
                } else {
                    weakLast.nextValidator = entry;
                }
                weakLast = entry;
                weakCount++;
            } else {
                if (strongLast == null) {
                    strongFirst = entry;
                } else {
                    strongLast.nextValidator = entry;
                }
                strongLast = entry;
                strongCount++;
            }

            occupancy += retainedContent.length;
            size++;
            lastAssignedId = id;
            return id;
        }
    }

    /**
     * Returns a detached, nonconsuming result, or null immediately on a miss.
     */
    public Message fetchIfMatch(long messageId, String validator) {
        if (messageId <= 0) {
            throw new IllegalArgumentException("Message ID must be positive");
        }
        if (validator == null) {
            throw new IllegalArgumentException("Validator must not be null");
        }

        synchronized (lock) {
            for (Entry entry = first; entry != null; entry = entry.next) {
                if (entry.id > messageId) {
                    break;
                }
                if (entry.id == messageId) {
                    if (!entry.validator.equals(validator)) {
                        return null;
                    }
                    return new Message(
                            entry.id,
                            entry.content.clone(),
                            entry.draft,
                            entry.validator);
                }
            }
            return null;
        }
    }

    public int prune(long throughId) {
        if (throughId < 0) {
            throw new IllegalArgumentException("Prune ID must be nonnegative");
        }

        synchronized (lock) {
            int removed = 0;
            while (first != null && first.id <= throughId) {
                removeFirst();
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
            for (Entry entry = first; entry != null; entry = entry.next) {
                messageIds[index++] = entry.id;
            }

            index = 0;
            for (Entry entry = strongFirst;
                    entry != null;
                    entry = entry.nextValidator) {
                strongValidators[index++] = entry.validator;
            }

            index = 0;
            for (Entry entry = weakFirst;
                    entry != null;
                    entry = entry.nextValidator) {
                weakValidators[index++] = entry.validator;
            }

            return new Header(
                    capacity, occupancy, messageIds,
                    strongValidators, weakValidators);
        }
    }

    // Called only while holding lock; FIFO order also orders each validator set.
    private void removeFirst() {
        Entry removed = first;
        first = removed.next;
        if (first == null) {
            last = null;
        }

        if (removed.draft) {
            weakFirst = removed.nextValidator;
            if (weakFirst == null) {
                weakLast = null;
            }
            weakCount--;
        } else {
            strongFirst = removed.nextValidator;
            if (strongFirst == null) {
                strongLast = null;
            }
            strongCount--;
        }

        occupancy -= removed.content.length;
        size--;
        removed.next = null;
        removed.nextValidator = null;
    }

    private static boolean sameContent(byte[] left, byte[] right) {
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
        private final long id;
        private final byte[] content;
        private final boolean draft;
        private final String validator;
        private Entry next;
        private Entry nextValidator;

        private Entry(long id, byte[] content, boolean draft) {
            this.id = id;
            this.content = content;
            this.draft = draft;
            this.validator = (draft ? "W-" : "S-") + id;
        }
    }

    public static final class Message {
        public final long messageId;
        public final boolean draft;
        public final String validator;
        private final byte[] content;

        private Message(
                long messageId, byte[] content, boolean draft, String validator) {
            this.messageId = messageId;
            this.content = content;
            this.draft = draft;
            this.validator = validator;
        }

        public byte[] getContent() {
            return content.clone();
        }
    }

    public static final class Header {
        public final int capacity;
        public final int occupancy;
        private final long[] messageIds;
        private final String[] strongValidators;
        private final String[] weakValidators;

        private Header(
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

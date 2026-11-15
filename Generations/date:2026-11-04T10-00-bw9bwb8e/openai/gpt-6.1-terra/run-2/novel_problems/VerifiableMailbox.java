@VerifiableMailbox.ThreadSafe
public final class VerifiableMailbox {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final long capacity;

    // All fields below are accessed only while holding lock.
    private long occupancy;
    private long lastId;
    private long messageCount;
    private long strongCount;
    private long weakCount;
    private Entry head;
    private Entry tail;
    private Entry strongHead;
    private Entry strongTail;
    private Entry weakHead;
    private Entry weakTail;

    public VerifiableMailbox(long capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
    }

    /**
     * Inserts immediately, evicting oldest entries if necessary. A duplicate
     * returns -1 without changing the mailbox.
     */
    public long receive(byte[] content, boolean draft) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("Content must not be null or empty");
        }
        if ((long) content.length > capacity) {
            throw new IllegalArgumentException("Content exceeds capacity");
        }

        byte[] copy = content.clone();

        synchronized (lock) {
            for (Entry entry = head; entry != null; entry = entry.next) {
                if (sameContent(entry.content, copy)) {
                    return -1;
                }
            }

            if (lastId == Long.MAX_VALUE) {
                throw new IllegalStateException("Message IDs exhausted");
            }

            long id = lastId + 1;
            Entry entry = new Entry(id, copy, draft);

            while ((long) copy.length > capacity - occupancy) {
                removeHead();
            }

            if (tail == null) {
                head = entry;
            } else {
                tail.next = entry;
            }
            tail = entry;
            occupancy += copy.length;
            messageCount++;
            addValidator(entry);
            lastId = id;
            return id;
        }
    }

    /**
     * Returns a detached message on an exact match, or null immediately.
     */
    public Message fetchIfMatch(long messageId, String validator) {
        if (messageId <= 0 || validator == null) {
            throw new IllegalArgumentException("Invalid ID or validator");
        }

        synchronized (lock) {
            for (Entry entry = head; entry != null; entry = entry.next) {
                if (entry.id == messageId) {
                    if (!entry.validator.equals(validator)) {
                        return null;
                    }
                    return new Message(
                            entry.id, entry.content.clone(),
                            entry.draft, entry.validator);
                }
                if (entry.id > messageId) {
                    break;
                }
            }
            return null;
        }
    }

    /**
     * Removes all entries through the specified ID and returns their count.
     */
    public long prune(long throughId) {
        if (throughId < 0) {
            throw new IllegalArgumentException("throughId must be nonnegative");
        }

        synchronized (lock) {
            long removed = 0;
            while (head != null && head.id <= throughId) {
                removeHead();
                removed++;
            }
            return removed;
        }
    }

    /**
     * Returns a coherent snapshot with arrays detached from mailbox state.
     */
    public Header snapshotHeader() {
        synchronized (lock) {
            long[] ids = new long[arrayLength(messageCount)];
            String[] strong = new String[arrayLength(strongCount)];
            String[] weak = new String[arrayLength(weakCount)];

            int i = 0;
            for (Entry entry = head; entry != null; entry = entry.next) {
                ids[i++] = entry.id;
            }

            i = 0;
            for (Entry entry = strongHead;
                    entry != null; entry = entry.nextValidator) {
                strong[i++] = entry.validator;
            }

            i = 0;
            for (Entry entry = weakHead;
                    entry != null; entry = entry.nextValidator) {
                weak[i++] = entry.validator;
            }

            return new Header(capacity, occupancy, ids, strong, weak);
        }
    }

    public static final class Message {
        public final long id;
        public final byte[] content;
        public final boolean draft;
        public final String validator;

        private Message(long id, byte[] content, boolean draft, String validator) {
            this.id = id;
            this.content = content;
            this.draft = draft;
            this.validator = validator;
        }
    }

    public static final class Header {
        public final long capacity;
        public final long occupancy;
        public final long[] messageIds;
        public final String[] strongValidators;
        public final String[] weakValidators;

        private Header(long capacity, long occupancy, long[] messageIds,
                       String[] strongValidators, String[] weakValidators) {
            this.capacity = capacity;
            this.occupancy = occupancy;
            this.messageIds = messageIds;
            this.strongValidators = strongValidators;
            this.weakValidators = weakValidators;
        }
    }

    private static final class Entry {
        final long id;
        final byte[] content;
        final boolean draft;
        final String validator;
        Entry next;
        Entry previousValidator;
        Entry nextValidator;

        Entry(long id, byte[] content, boolean draft) {
            this.id = id;
            this.content = content;
            this.draft = draft;
            this.validator = (draft ? "W-" : "S-") + id;
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

    private static int arrayLength(long count) {
        if (count > Integer.MAX_VALUE) {
            throw new OutOfMemoryError("Snapshot array is too large");
        }
        return (int) count;
    }

    // These helpers are called only while holding lock.
    private void addValidator(Entry entry) {
        if (entry.draft) {
            entry.previousValidator = weakTail;
            if (weakTail == null) {
                weakHead = entry;
            } else {
                weakTail.nextValidator = entry;
            }
            weakTail = entry;
            weakCount++;
        } else {
            entry.previousValidator = strongTail;
            if (strongTail == null) {
                strongHead = entry;
            } else {
                strongTail.nextValidator = entry;
            }
            strongTail = entry;
            strongCount++;
        }
    }

    private void removeHead() {
        Entry entry = head;
        head = entry.next;
        if (head == null) {
            tail = null;
        }
        occupancy -= entry.content.length;
        messageCount--;

        Entry previous = entry.previousValidator;
        Entry next = entry.nextValidator;
        if (previous != null) {
            previous.nextValidator = next;
        } else if (entry.draft) {
            weakHead = next;
        } else {
            strongHead = next;
        }

        if (next != null) {
            next.previousValidator = previous;
        } else if (entry.draft) {
            weakTail = previous;
        } else {
            strongTail = previous;
        }

        if (entry.draft) {
            weakCount--;
        } else {
            strongCount--;
        }
    }
}

import java.lang.String;

@VerifiableMailbox.ThreadSafe
public final class VerifiableMailbox {
    @interface ThreadSafe {}


    /** Detached, immutable view of a stored message. */
    public static final class Message {
        private final long id;
        private final boolean draft;
        private final String validator;
        private final byte[] content;

        private Message(long id, boolean draft, String validator, byte[] content) {
            this.id = id;
            this.draft = draft;
            this.validator = validator;
            this.content = content;
        }

        public long getId() {
            return id;
        }

        public boolean isDraft() {
            return draft;
        }

        public String getValidator() {
            return validator;
        }

        /** Returns a fresh copy of the content so callers cannot alter stored data. */
        public byte[] getContent() {
            return copyOf(content);
        }
    }

    /** Coherent point-in-time snapshot of the mailbox header. */
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

        /** Copy of the ordered (oldest first) message IDs. */
        public long[] getMessageIds() {
            long[] r = new long[messageIds.length];
            for (int i = 0; i < messageIds.length; i++) {
                r[i] = messageIds[i];
            }
            return r;
        }

        /** Copy of the strong validators (nondraft messages, oldest first). */
        public String[] getStrongValidators() {
            String[] r = new String[strongValidators.length];
            for (int i = 0; i < strongValidators.length; i++) {
                r[i] = strongValidators[i];
            }
            return r;
        }

        /** Copy of the weak validators (draft messages, oldest first). */
        public String[] getWeakValidators() {
            String[] r = new String[weakValidators.length];
            for (int i = 0; i < weakValidators.length; i++) {
                r[i] = weakValidators[i];
            }
            return r;
        }
    }

    private static final class Entry {
        final long id;
        final boolean draft;
        final String validator;
        final byte[] content;
        Entry next;

        Entry(long id, boolean draft, String validator, byte[] content) {
            this.id = id;
            this.draft = draft;
            this.validator = validator;
            this.content = content;
        }
    }

    // Policy: no operation blocks. receive evicts oldest messages to make room,
    // fetchIfMatch returns null when nothing matches, prune returns 0 when nothing
    // qualifies, and exhaustion of IDs is reported with IllegalStateException.
    // All state is guarded by 'lock'; no waits are needed because no operation
    // waits for a condition.

    private final int capacity;
    private final Object lock = new Object();

    // FIFO inbox: head is oldest, tail is newest. IDs are strictly increasing along the list.
    private Entry head;
    private Entry tail;
    private int count;
    private int occupancy;

    // Next ID to assign; becomes non-positive once the positive long range is exhausted.
    private long nextId = 1L;

    public VerifiableMailbox(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    /**
     * Stores content as a new message at the tail of the inbox.
     * Returns -1 if byte-identical content is already stored (no mutation).
     * Otherwise evicts oldest messages as needed and returns the new positive ID.
     */
    public long receive(byte[] content, boolean draft) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("content must be non-null and non-empty");
        }
        if (content.length > capacity) {
            throw new IllegalArgumentException("content exceeds total capacity");
        }
        byte[] copy = copyOf(content);
        int len = copy.length;

        synchronized (lock) {
            for (Entry e = head; e != null; e = e.next) {
                if (e.content.length == len && bytesEqual(e.content, copy)) {
                    return -1L;
                }
            }

            if (nextId <= 0L) {
                throw new IllegalStateException("message ID space exhausted");
            }
            long id = nextId;

            while (occupancy + len > capacity) {
                removeHead();
            }

            nextId = nextId + 1L; // wraps to non-positive after Long.MAX_VALUE
            String validator = draft ? "W-" + id : "S-" + id;
            Entry e = new Entry(id, draft, validator, copy);
            if (tail == null) {
                head = e;
                tail = e;
            } else {
                tail.next = e;
                tail = e;
            }
            count++;
            occupancy += len;
            return id;
        }
    }

    /**
     * Returns a detached copy of the message if both ID and validator match a
     * currently stored entry; otherwise null. Does not consume the message.
     */
    public Message fetchIfMatch(long messageId, String validator) {
        if (messageId <= 0L) {
            throw new IllegalArgumentException("messageId must be positive");
        }
        if (validator == null) {
            throw new IllegalArgumentException("validator must be non-null");
        }
        synchronized (lock) {
            for (Entry e = head; e != null; e = e.next) {
                if (e.id == messageId) {
                    if (e.validator.equals(validator)) {
                        return new Message(e.id, e.draft, e.validator, copyOf(e.content));
                    }
                    return null;
                }
                if (e.id > messageId) {
                    return null;
                }
            }
            return null;
        }
    }

    /**
     * Atomically removes all messages with ID <= throughId and returns the count removed.
     */
    public int prune(long throughId) {
        if (throughId < 0L) {
            throw new IllegalArgumentException("throughId must be nonnegative");
        }
        synchronized (lock) {
            int removed = 0;
            while (head != null && head.id <= throughId) {
                removeHead();
                removed++;
            }
            return removed;
        }
    }

    /**
     * Returns a coherent snapshot of capacity, occupancy, ordered IDs, and validators.
     */
    public Header snapshotHeader() {
        synchronized (lock) {
            long[] ids = new long[count];
            int strongCount = 0;
            int weakCount = 0;
            for (Entry e = head; e != null; e = e.next) {
                if (e.draft) {
                    weakCount++;
                } else {
                    strongCount++;
                }
            }
            String[] strong = new String[strongCount];
            String[] weak = new String[weakCount];
            int i = 0;
            int si = 0;
            int wi = 0;
            for (Entry e = head; e != null; e = e.next) {
                ids[i++] = e.id;
                if (e.draft) {
                    weak[wi++] = e.validator;
                } else {
                    strong[si++] = e.validator;
                }
            }
            return new Header(capacity, occupancy, ids, strong, weak);
        }
    }

    // Caller must hold lock and ensure the list is non-empty.
    private void removeHead() {
        Entry old = head;
        head = old.next;
        if (head == null) {
            tail = null;
        }
        old.next = null;
        count--;
        occupancy -= old.content.length;
    }

    private static byte[] copyOf(byte[] src) {
        byte[] dst = new byte[src.length];
        for (int i = 0; i < src.length; i++) {
            dst[i] = src[i];
        }
        return dst;
    }

    private static boolean bytesEqual(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }
}

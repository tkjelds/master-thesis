@VerifiableMailbox.ThreadSafe
public final class VerifiableMailbox {
    @interface ThreadSafe {}


    public static final class Message {
        private final long id;
        private final boolean draft;
        private final String validator;
        private final byte[] content;

        private Message(long id, boolean draft, String validator, byte[] content) {
            this.id = id;
            this.draft = draft;
            this.validator = validator;
            this.content = copyBytes(content);
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

        public byte[] getContent() {
            return copyBytes(content);
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

    private static final class Entry {
        final long id;
        final boolean draft;
        final String validator;
        final byte[] content;

        Entry(long id, boolean draft, String validator, byte[] content) {
            this.id = id;
            this.draft = draft;
            this.validator = validator;
            this.content = content;
        }
    }

    private final int capacity;
    private final Object lock = new Object();

    // FIFO inbox as a circular buffer; ordered by strictly increasing ID.
    private Entry[] ring;
    private int head;
    private int size;

    private int occupancy;
    private long nextId = 1L;
    private boolean idsExhausted = false;

    public VerifiableMailbox(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.ring = new Entry[8];
        this.head = 0;
        this.size = 0;
        this.occupancy = 0;
    }

    /**
     * Stores content, evicting oldest messages as needed. Returns the new positive
     * message ID, or -1 if byte-identical content is already stored (no mutation).
     * Never blocks: capacity is reclaimed by eviction, not by waiting.
     */
    public long receive(byte[] content, boolean draft) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("content must be non-null and non-empty");
        }
        if (content.length > capacity) {
            throw new IllegalArgumentException("content exceeds total capacity");
        }
        byte[] copy = copyBytes(content);

        synchronized (lock) {
            // Duplicate check across all stored messages, regardless of draft status.
            for (int i = 0; i < size; i++) {
                Entry e = ring[indexOf(i)];
                if (bytesEqual(e.content, copy)) {
                    return -1L;
                }
            }

            // ID exhaustion must be rejected before any eviction.
            if (idsExhausted) {
                throw new IllegalStateException("message ID space exhausted");
            }

            // Evict oldest messages until the new content fits.
            while (occupancy + copy.length > capacity) {
                removeHead();
            }

            long id = nextId;
            if (id == Long.MAX_VALUE) {
                idsExhausted = true;
            } else {
                nextId = id + 1L;
            }

            String validator = draft ? ("W-" + id) : ("S-" + id);
            appendTail(new Entry(id, draft, validator, copy));
            occupancy += copy.length;
            return id;
        }
    }

    /**
     * Returns a detached copy of the message only if both ID and exact validator
     * match a currently stored entry; otherwise null. Does not consume the message.
     */
    public Message fetchIfMatch(long messageId, String validator) {
        if (messageId <= 0L) {
            throw new IllegalArgumentException("messageId must be positive");
        }
        if (validator == null) {
            throw new IllegalArgumentException("validator must be non-null");
        }
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                Entry e = ring[indexOf(i)];
                if (e.id == messageId) {
                    if (e.validator.equals(validator)) {
                        return new Message(e.id, e.draft, e.validator, e.content);
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
            while (size > 0 && ring[head].id <= throughId) {
                removeHead();
                removed++;
            }
            return removed;
        }
    }

    /**
     * Returns a coherent snapshot of capacity, occupancy, ordered IDs, and the
     * strong and weak validator arrays, all observed under one lock acquisition.
     */
    public Header snapshotHeader() {
        synchronized (lock) {
            long[] ids = new long[size];
            int strongCount = 0;
            int weakCount = 0;
            for (int i = 0; i < size; i++) {
                Entry e = ring[indexOf(i)];
                ids[i] = e.id;
                if (e.draft) {
                    weakCount++;
                } else {
                    strongCount++;
                }
            }
            String[] strong = new String[strongCount];
            String[] weak = new String[weakCount];
            int si = 0;
            int wi = 0;
            for (int i = 0; i < size; i++) {
                Entry e = ring[indexOf(i)];
                if (e.draft) {
                    weak[wi++] = e.validator;
                } else {
                    strong[si++] = e.validator;
                }
            }
            return new Header(capacity, occupancy, ids, strong, weak);
        }
    }

    // ---- Private helpers; all callers hold 'lock'. ----

    private int indexOf(int logicalIndex) {
        return (head + logicalIndex) % ring.length;
    }

    private void appendTail(Entry e) {
        if (size == ring.length) {
            Entry[] grown = new Entry[ring.length * 2];
            for (int i = 0; i < size; i++) {
                grown[i] = ring[indexOf(i)];
            }
            ring = grown;
            head = 0;
        }
        ring[indexOf(size)] = e;
        size++;
    }

    private void removeHead() {
        Entry e = ring[head];
        ring[head] = null;
        head = (head + 1) % ring.length;
        size--;
        occupancy -= e.content.length;
        if (size == 0) {
            head = 0;
        }
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

    private static byte[] copyBytes(byte[] src) {
        byte[] dst = new byte[src.length];
        System.arraycopy(src, 0, dst, 0, src.length);
        return dst;
    }

    private static long[] copyLongs(long[] src) {
        long[] dst = new long[src.length];
        System.arraycopy(src, 0, dst, 0, src.length);
        return dst;
    }

    private static String[] copyStrings(String[] src) {
        String[] dst = new String[src.length];
        System.arraycopy(src, 0, dst, 0, src.length);
        return dst;
    }
}

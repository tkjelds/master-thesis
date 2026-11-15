@VerifiableMailbox.ThreadSafe
public final class VerifiableMailbox {
    @interface ThreadSafe {}

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

    private static final class Entry {
        final long id;
        final byte[] content;
        final boolean draft;
        final String validator;
        Entry next;
        Entry nextValidator;

        Entry(long id, byte[] content, boolean draft) {
            this.id = id;
            this.content = content;
            this.draft = draft;
            this.validator = (draft ? "W-" : "S-") + id;
        }
    }

    private final Object lock = new Object();
    private final int capacity;
    private int occupancy;
    private int count;
    private int strongCount;
    private int weakCount;
    private long lastId;
    private Entry head;
    private Entry tail;
    private Entry strongHead;
    private Entry strongTail;
    private Entry weakHead;
    private Entry weakTail;

    public VerifiableMailbox(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    public long receive(byte[] content, boolean draft) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("content must not be null or empty");
        }
        if (content.length > capacity) {
            throw new IllegalArgumentException("content exceeds capacity");
        }
        byte[] copy = copyOf(content);
        synchronized (lock) {
            for (Entry entry = head; entry != null; entry = entry.next) {
                if (sameContent(entry.content, copy)) {
                    return -1;
                }
            }
            if (lastId == 9223372036854775807L) {
                throw new IllegalStateException("message IDs exhausted");
            }
            Entry entry = new Entry(lastId + 1, copy, draft);
            while (occupancy > capacity - copy.length) {
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
            occupancy += copy.length;
            count++;
            lastId = entry.id;
            return entry.id;
        }
    }

    public Message fetchIfMatch(long messageId, String validator) {
        if (messageId <= 0 || validator == null) {
            throw new IllegalArgumentException("invalid ID or validator");
        }
        synchronized (lock) {
            for (Entry entry = head; entry != null; entry = entry.next) {
                if (entry.id == messageId) {
                    if (!entry.validator.equals(validator)) {
                        return null;
                    }
                    return new Message(entry.id, copyOf(entry.content),
                                       entry.draft, entry.validator);
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

    public Header snapshotHeader() {
        synchronized (lock) {
            long[] ids = new long[count];
            String[] strong = new String[strongCount];
            String[] weak = new String[weakCount];
            int i = 0;
            for (Entry entry = head; entry != null; entry = entry.next) {
                ids[i++] = entry.id;
            }
            i = 0;
            for (Entry entry = strongHead; entry != null; entry = entry.nextValidator) {
                strong[i++] = entry.validator;
            }
            i = 0;
            for (Entry entry = weakHead; entry != null; entry = entry.nextValidator) {
                weak[i++] = entry.validator;
            }
            return new Header(capacity, occupancy, ids, strong, weak);
        }
    }

    private void removeOldest() {
        Entry entry = head;
        head = entry.next;
        if (head == null) {
            tail = null;
        }
        if (entry.draft) {
            weakHead = entry.nextValidator;
            if (weakHead == null) {
                weakTail = null;
            }
            weakCount--;
        } else {
            strongHead = entry.nextValidator;
            if (strongHead == null) {
                strongTail = null;
            }
            strongCount--;
        }
        occupancy -= entry.content.length;
        count--;
    }

    private static byte[] copyOf(byte[] source) {
        byte[] copy = new byte[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
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
}

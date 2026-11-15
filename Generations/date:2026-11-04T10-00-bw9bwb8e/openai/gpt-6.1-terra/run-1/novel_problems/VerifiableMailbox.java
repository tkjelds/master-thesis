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

    private Entry first;
    private Entry last;
    private Entry firstStrong;
    private Entry lastStrong;
    private Entry firstWeak;
    private Entry lastWeak;

    private static final class Entry {
        final long id;
        final byte[] content;
        final boolean draft;
        final String validator;

        Entry next;
        Entry nextValidator;

        Entry(long id, byte[] content, boolean draft, String validator) {
            this.id = id;
            this.content = content;
            this.draft = draft;
            this.validator = validator;
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
                String[] weakValidators) {
            this.capacity = capacity;
            this.occupancy = occupancy;
            this.messageIds = messageIds;
            this.strongValidators = strongValidators;
            this.weakValidators = weakValidators;
        }
    }

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

        byte[] retained = copyBytes(content);

        synchronized (lock) {
            for (Entry entry = first; entry != null; entry = entry.next) {
                if (sameBytes(entry.content, retained)) {
                    return -1;
                }
            }

            if (lastAssignedId == Long.MAX_VALUE) {
                throw new IllegalStateException("message IDs exhausted");
            }

            long id = lastAssignedId + 1;
            String validator = (draft ? "W-" : "S-") + id;
            Entry added = new Entry(id, retained, draft, validator);

            // Capacity is reclaimed immediately; receive never waits for space.
            while (capacity - occupancy < retained.length) {
                removeFirst();
            }

            if (last == null) {
                first = added;
            } else {
                last.next = added;
            }
            last = added;

            if (draft) {
                if (lastWeak == null) {
                    firstWeak = added;
                } else {
                    lastWeak.nextValidator = added;
                }
                lastWeak = added;
                weakCount++;
            } else {
                if (lastStrong == null) {
                    firstStrong = added;
                } else {
                    lastStrong.nextValidator = added;
                }
                lastStrong = added;
                strongCount++;
            }

            occupancy += retained.length;
            messageCount++;
            lastAssignedId = id;
            return id;
        }
    }

    public Message fetchIfMatch(long messageId, String validator) {
        if (messageId <= 0) {
            throw new IllegalArgumentException("messageId must be positive");
        }
        if (validator == null) {
            throw new IllegalArgumentException("validator must not be null");
        }

        synchronized (lock) {
            for (Entry entry = first; entry != null; entry = entry.next) {
                if (entry.id == messageId) {
                    if (!entry.validator.equals(validator)) {
                        return null;
                    }
                    return new Message(
                            entry.id,
                            copyBytes(entry.content),
                            entry.draft,
                            entry.validator);
                }
                if (entry.id > messageId) {
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
            while (first != null && first.id <= throughId) {
                removeFirst();
                removed++;
            }
            return removed;
        }
    }

    public Header snapshotHeader() {
        synchronized (lock) {
            long[] ids = new long[messageCount];
            String[] strong = new String[strongCount];
            String[] weak = new String[weakCount];

            int index = 0;
            for (Entry entry = first; entry != null; entry = entry.next) {
                ids[index++] = entry.id;
            }

            index = 0;
            for (Entry entry = firstStrong; entry != null; entry = entry.nextValidator) {
                strong[index++] = entry.validator;
            }

            index = 0;
            for (Entry entry = firstWeak; entry != null; entry = entry.nextValidator) {
                weak[index++] = entry.validator;
            }

            return new Header(capacity, occupancy, ids, strong, weak);
        }
    }

    // Called only while holding lock and when the inbox is nonempty.
    private void removeFirst() {
        Entry removed = first;
        first = removed.next;
        if (first == null) {
            last = null;
        }

        if (removed.draft) {
            firstWeak = removed.nextValidator;
            if (firstWeak == null) {
                lastWeak = null;
            }
            weakCount--;
        } else {
            firstStrong = removed.nextValidator;
            if (firstStrong == null) {
                lastStrong = null;
            }
            strongCount--;
        }

        occupancy -= removed.content.length;
        messageCount--;
    }

    private static byte[] copyBytes(byte[] source) {
        byte[] copy = new byte[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
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
}

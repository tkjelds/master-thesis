import java.lang.*;

@VerifiableMailbox.ThreadSafe
public class VerifiableMailbox {
    @interface ThreadSafe {}


    public static final class Message {
        private final long id;
        private final String validator;
        private final boolean draft;
        private final byte[] content;

        Message(long id, String validator, boolean draft, byte[] content) {
            this.id = id;
            this.validator = validator;
            this.draft = draft;
            this.content = content;
        }

        public long getId() {
            return id;
        }

        public String getValidator() {
            return validator;
        }

        public boolean isDraft() {
            return draft;
        }

        public byte[] getContent() {
            return content.clone();
        }
    }

    public static final class Header {
        private final long capacity;
        private final long occupancy;
        private final long[] ids;
        private final String[] strong;
        private final String[] weak;

        Header(long capacity, long occupancy, long[] ids, String[] strong, String[] weak) {
            this.capacity = capacity;
            this.occupancy = occupancy;
            this.ids = ids;
            this.strong = strong;
            this.weak = weak;
        }

        public long getCapacity() {
            return capacity;
        }

        public long getOccupancy() {
            return occupancy;
        }

        public long[] getMessageIds() {
            return ids.clone();
        }

        public String[] getStrongValidators() {
            return strong.clone();
        }

        public String[] getWeakValidators() {
            return weak.clone();
        }
    }

    private static final class Entry {
        final long id;
        final byte[] content;
        final boolean draft;
        final String validator;

        Entry(long id, byte[] content, boolean draft, String validator) {
            this.id = id;
            this.content = content;
            this.draft = draft;
            this.validator = validator;
        }
    }

    private static final class StringQueue {
        private String[] a = new String[8];
        private int head = 0;
        private int size = 0;

        void add(String s) {
            if (size == a.length) {
                String[] n = new String[a.length * 2];
                for (int i = 0; i < size; i++) {
                    n[i] = a[(head + i) % a.length];
                }
                a = n;
                head = 0;
            }
            a[(head + size) % a.length] = s;
            size++;
        }

        void removeFirst() {
            if (size == 0) {
                return;
            }
            a[head] = null;
            head = (head + 1) % a.length;
            size--;
        }

        String[] toArray() {
            String[] r = new String[size];
            for (int i = 0; i < size; i++) {
                r[i] = a[(head + i) % a.length];
            }
            return r;
        }
    }

    private final Object lock = new Object();
    private final long capacity;
    private Entry[] ring = new Entry[16];
    private int head = 0;
    private int size = 0;
    private long occupancy = 0;
    private long nextId = 1;
    private final StringQueue weak = new StringQueue();
    private final StringQueue strong = new StringQueue();

    public VerifiableMailbox(long capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    private static boolean sameBytes(byte[] x, byte[] y) {
        if (x.length != y.length) {
            return false;
        }
        for (int i = 0; i < x.length; i++) {
            if (x[i] != y[i]) {
                return false;
            }
        }
        return true;
    }

    // Must hold lock.
    private void removeOldest() {
        Entry e = ring[head];
        ring[head] = null;
        head = (head + 1) % ring.length;
        size--;
        occupancy -= e.content.length;
        if (e.draft) {
            weak.removeFirst();
        } else {
            strong.removeFirst();
        }
    }

    public long receive(byte[] content, boolean draft) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("content must be non-empty");
        }
        if (content.length > capacity) {
            throw new IllegalArgumentException("content exceeds capacity");
        }
        byte[] copy = content.clone();
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                Entry e = ring[(head + i) % ring.length];
                if (sameBytes(e.content, copy)) {
                    return -1;
                }
            }
            if (nextId == Long.MAX_VALUE) {
                throw new IllegalStateException("message ids exhausted");
            }
            while (occupancy + copy.length > capacity) {
                removeOldest();
            }
            long id = nextId++;
            String validator = (draft ? "W-" : "S-") + id;
            if (size == ring.length) {
                Entry[] n = new Entry[ring.length * 2];
                for (int i = 0; i < size; i++) {
                    n[i] = ring[(head + i) % ring.length];
                }
                ring = n;
                head = 0;
            }
            ring[(head + size) % ring.length] = new Entry(id, copy, draft, validator);
            size++;
            occupancy += copy.length;
            if (draft) {
                weak.add(validator);
            } else {
                strong.add(validator);
            }
            return id;
        }
    }

    public Message fetchIfMatch(long messageId, String validator) {
        if (messageId <= 0) {
            throw new IllegalArgumentException("id must be positive");
        }
        if (validator == null) {
            throw new IllegalArgumentException("validator must not be null");
        }
        synchronized (lock) {
            int lo = 0;
            int hi = size - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                Entry e = ring[(head + mid) % ring.length];
                if (e.id == messageId) {
                    if (e.validator.equals(validator)) {
                        return new Message(e.id, e.validator, e.draft, e.content.clone());
                    }
                    return null;
                } else if (e.id < messageId) {
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
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
            while (size > 0 && ring[head].id <= throughId) {
                removeOldest();
                removed++;
            }
            return removed;
        }
    }

    public Header snapshotHeader() {
        synchronized (lock) {
            long[] ids = new long[size];
            for (int i = 0; i < size; i++) {
                ids[i] = ring[(head + i) % ring.length].id;
            }
            return new Header(capacity, occupancy, ids, strong.toArray(), weak.toArray());
        }
    }
}

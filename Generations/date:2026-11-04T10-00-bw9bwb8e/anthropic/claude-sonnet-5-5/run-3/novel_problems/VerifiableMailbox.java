import java.lang.System;

@VerifiableMailbox.ThreadSafe
public class VerifiableMailbox {
    @interface ThreadSafe {}


    public static final class Message {
        private final long id;
        private final byte[] content;
        private final boolean draft;
        private final String validator;

        private Message(long id, byte[] content, boolean draft, String validator) {
            this.id = id;
            this.content = content;
            this.draft = draft;
            this.validator = validator;
        }

        public long getId() {
            return id;
        }

        public byte[] getContent() {
            return content.clone();
        }

        public boolean isDraft() {
            return draft;
        }

        public String getValidator() {
            return validator;
        }
    }

    public static final class Header {
        private final long capacity;
        private final long occupancy;
        private final long[] ids;
        private final String[] strong;
        private final String[] weak;

        private Header(long capacity, long occupancy, long[] ids, String[] strong, String[] weak) {
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

        public long[] getIds() {
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

    private static final class ValidatorSet {
        private String[] items = new String[8];
        private int n = 0;

        void add(String s) {
            if (indexOf(s) >= 0) {
                throw new IllegalStateException("duplicate validator");
            }
            if (n == items.length) {
                String[] bigger = new String[n * 2];
                System.arraycopy(items, 0, bigger, 0, n);
                items = bigger;
            }
            items[n++] = s;
        }

        int indexOf(String s) {
            for (int i = 0; i < n; i++) {
                if (items[i].equals(s)) {
                    return i;
                }
            }
            return -1;
        }

        void remove(String s) {
            int i = indexOf(s);
            if (i < 0) {
                throw new IllegalStateException("validator missing");
            }
            System.arraycopy(items, i + 1, items, i, n - i - 1);
            items[--n] = null;
        }

        String[] toArray() {
            String[] r = new String[n];
            System.arraycopy(items, 0, r, 0, n);
            return r;
        }
    }

    private final Object lock = new Object();
    private final long capacity;
    private long occupancy = 0;
    private long nextId = 1;
    private Entry[] entries = new Entry[8];
    private int size = 0;
    private final ValidatorSet strongSet = new ValidatorSet();
    private final ValidatorSet weakSet = new ValidatorSet();

    public VerifiableMailbox(long capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    private static boolean sameBytes(byte[] a, byte[] b) {
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

    // Must hold lock. Removes the oldest entry.
    private void removeOldest() {
        Entry e = entries[0];
        System.arraycopy(entries, 1, entries, 0, size - 1);
        entries[--size] = null;
        occupancy -= e.content.length;
        if (e.draft) {
            weakSet.remove(e.validator);
        } else {
            strongSet.remove(e.validator);
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
                if (sameBytes(entries[i].content, copy)) {
                    return -1;
                }
            }
            if (nextId <= 0) {
                throw new IllegalStateException("ID space exhausted");
            }
            while (capacity - occupancy < copy.length) {
                removeOldest();
            }
            long id = nextId;
            nextId = id + 1; // becomes negative after Long.MAX_VALUE: exhausted
            String validator = (draft ? "W-" : "S-") + id;
            if (size == entries.length) {
                Entry[] bigger = new Entry[size * 2];
                System.arraycopy(entries, 0, bigger, 0, size);
                entries = bigger;
            }
            entries[size++] = new Entry(id, copy, draft, validator);
            occupancy += copy.length;
            if (draft) {
                weakSet.add(validator);
            } else {
                strongSet.add(validator);
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
            for (int i = 0; i < size; i++) {
                Entry e = entries[i];
                if (e.id == messageId) {
                    if (e.validator.equals(validator)) {
                        return new Message(e.id, e.content.clone(), e.draft, e.validator);
                    }
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
            while (size > 0 && entries[0].id <= throughId) {
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
                ids[i] = entries[i].id;
            }
            return new Header(capacity, occupancy, ids, strongSet.toArray(), weakSet.toArray());
        }
    }
}

import java.lang.*;

@VerifiableMailbox.ThreadSafe
public class VerifiableMailbox {
    @interface ThreadSafe {}


    public static final class Message {
        private final long id;
        private final byte[] content;
        private final boolean draft;
        private final String validator;

        Message(long id, byte[] content, boolean draft, String validator) {
            this.id = id;
            this.content = content;
            this.draft = draft;
            this.validator = validator;
        }

        public long getId() {
            return id;
        }

        public byte[] getContent() {
            return copy(content);
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
            long[] r = new long[ids.length];
            for (int i = 0; i < r.length; i++) r[i] = ids[i];
            return r;
        }

        public String[] getStrongValidators() {
            String[] r = new String[strong.length];
            for (int i = 0; i < r.length; i++) r[i] = strong[i];
            return r;
        }

        public String[] getWeakValidators() {
            String[] r = new String[weak.length];
            for (int i = 0; i < r.length; i++) r[i] = weak[i];
            return r;
        }
    }

    private static final class StringSet {
        private String[] items = new String[8];
        private int size = 0;

        void add(String s) {
            if (size == items.length) {
                String[] n = new String[size * 2];
                for (int i = 0; i < size; i++) n[i] = items[i];
                items = n;
            }
            items[size++] = s;
        }

        void remove(String s) {
            for (int i = 0; i < size; i++) {
                if (items[i].equals(s)) {
                    for (int j = i + 1; j < size; j++) items[j - 1] = items[j];
                    items[--size] = null;
                    return;
                }
            }
        }

        String[] toArray() {
            String[] r = new String[size];
            for (int i = 0; i < size; i++) r[i] = items[i];
            return r;
        }
    }

    private final Object lock = new Object();
    private final long capacity;
    private long occupancy = 0;
    private long lastId = 0;
    private Message[] ring = new Message[16];
    private int head = 0;
    private int count = 0;
    private final StringSet weakSet = new StringSet();
    private final StringSet strongSet = new StringSet();

    public VerifiableMailbox(long capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    private static byte[] copy(byte[] b) {
        byte[] r = new byte[b.length];
        for (int i = 0; i < b.length; i++) r[i] = b[i];
        return r;
    }

    private static boolean same(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) return false;
        }
        return true;
    }

    private Message at(int i) {
        return ring[(head + i) % ring.length];
    }

    private void removeHead() {
        Message m = ring[head];
        ring[head] = null;
        head = (head + 1) % ring.length;
        count--;
        occupancy -= m.content.length;
        if (m.draft) weakSet.remove(m.validator);
        else strongSet.remove(m.validator);
    }

    private void append(Message m) {
        if (count == ring.length) {
            Message[] n = new Message[ring.length * 2];
            for (int i = 0; i < count; i++) n[i] = at(i);
            ring = n;
            head = 0;
        }
        ring[(head + count) % ring.length] = m;
        count++;
        occupancy += m.content.length;
        if (m.draft) weakSet.add(m.validator);
        else strongSet.add(m.validator);
    }

    public long receive(byte[] content, boolean draft) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("content must be non-empty");
        }
        if ((long) content.length > capacity) {
            throw new IllegalArgumentException("content exceeds capacity");
        }
        byte[] mine = copy(content);
        synchronized (lock) {
            for (int i = 0; i < count; i++) {
                if (same(at(i).content, mine)) return -1;
            }
            if (lastId == Long.MAX_VALUE) {
                throw new IllegalStateException("message ids exhausted");
            }
            while (occupancy + mine.length > capacity) {
                removeHead();
            }
            long id = lastId + 1;
            lastId = id;
            String v = (draft ? "W-" : "S-") + id;
            append(new Message(id, mine, draft, v));
            return id;
        }
    }

    public Message fetchIfMatch(long messageId, String validator) {
        if (messageId <= 0) throw new IllegalArgumentException("id must be positive");
        if (validator == null) throw new IllegalArgumentException("validator is null");
        synchronized (lock) {
            for (int i = 0; i < count; i++) {
                Message m = at(i);
                if (m.id == messageId) {
                    if (m.validator.equals(validator)) {
                        return new Message(m.id, copy(m.content), m.draft, m.validator);
                    }
                    return null;
                }
            }
            return null;
        }
    }

    public int prune(long throughId) {
        if (throughId < 0) throw new IllegalArgumentException("throughId must be nonnegative");
        synchronized (lock) {
            int removed = 0;
            while (count > 0 && ring[head].id <= throughId) {
                removeHead();
                removed++;
            }
            return removed;
        }
    }

    public Header snapshotHeader() {
        synchronized (lock) {
            long[] ids = new long[count];
            for (int i = 0; i < count; i++) ids[i] = at(i).id;
            return new Header(capacity, occupancy, ids, strongSet.toArray(), weakSet.toArray());
        }
    }
}

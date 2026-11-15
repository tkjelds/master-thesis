import java.lang.*;

@TemporalForexCache.ThreadSafe
public class TemporalForexCache {
    @interface ThreadSafe {}


    public interface Clock {
        long now();
    }

    /** Immutable result of a conversion. */
    public static final class Conversion {
        private final double value;
        private final long revision;
        private final long observationTime;

        Conversion(double value, long revision, long observationTime) {
            this.value = value;
            this.revision = revision;
            this.observationTime = observationTime;
        }

        public double value() { return value; }
        public long revision() { return revision; }
        public long observationTime() { return observationTime; }

        @Override
        public String toString() {
            return "Conversion[value=" + value + ", revision=" + revision
                    + ", observationTime=" + observationTime + "]";
        }
    }

    /** Immutable rate/expiration record. */
    private static final class Entry {
        final double rate;
        final long expiration;

        Entry(double rate, long expiration) {
            this.rate = rate;
            this.expiration = expiration;
        }
    }

    /** Immutable copied snapshot entry. */
    public static final class RateEntry {
        private final String currency;
        private final double rate;
        private final long expiration;

        RateEntry(String currency, double rate, long expiration) {
            this.currency = currency;
            this.rate = rate;
            this.expiration = expiration;
        }

        public String currency() { return currency; }
        public double rate() { return rate; }
        public long expiration() { return expiration; }
    }

    /** Immutable coherent snapshot. */
    public static final class Snapshot {
        private final RateEntry[] entries;
        private final long revision;
        private final long observationTime;

        Snapshot(RateEntry[] entries, long revision, long observationTime) {
            this.entries = entries;
            this.revision = revision;
            this.observationTime = observationTime;
        }

        public RateEntry[] entries() { return entries.clone(); }
        public long revision() { return revision; }
        public long observationTime() { return observationTime; }
        public int size() { return entries.length; }
    }

    private static final class Node {
        final String key;
        Entry entry;   // replaced only under lock; Entry itself is immutable
        Node next;

        Node(String key, Entry entry, Node next) {
            this.key = key;
            this.entry = entry;
            this.next = next;
        }
    }

    private final Clock clock;
    private final Object lock = new Object();

    // All guarded by lock
    private Node[] table = new Node[16];
    private int size = 0;
    private long revision = 0;

    public TemporalForexCache(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.clock = clock;
    }

    private static int hashOf(String key, int length) {
        int h = key.hashCode();
        h ^= (h >>> 16);
        return h & (length - 1);
    }

    private Node find(String key) {
        Node n = table[hashOf(key, table.length)];
        while (n != null) {
            if (n.key.equals(key)) return n;
            n = n.next;
        }
        return null;
    }

    private void resizeIfNeeded(int additional) {
        long needed = (long) size + additional;
        int cap = table.length;
        if (needed * 4 <= (long) cap * 3) return;
        long newCap = cap;
        while (needed * 4 > newCap * 3) newCap <<= 1;
        if (newCap > (1 << 30)) newCap = 1 << 30;
        Node[] nt = new Node[(int) newCap];
        for (int i = 0; i < table.length; i++) {
            Node n = table[i];
            while (n != null) {
                Node next = n.next;
                int idx = hashOf(n.key, nt.length);
                n.next = nt[idx];
                nt[idx] = n;
                n = next;
            }
        }
        table = nt;
    }

    private static void validateName(String s) {
        if (s == null || s.isEmpty()) {
            throw new IllegalArgumentException("invalid currency name");
        }
    }

    private static void validateRate(double r) {
        if (!(r > 0.0) || Double.isInfinite(r) || Double.isNaN(r)) {
            throw new IllegalArgumentException("invalid rate");
        }
    }

    public void updateRate(String currency, double rate, long ttl) {
        validateName(currency);
        validateRate(rate);
        if (ttl <= 0) throw new IllegalArgumentException("ttl must be positive");
        commit(new String[] { currency }, new double[] { rate }, ttl);
    }

    public void updateRatesBatch(String[] currencies, double[] rates, long ttl) {
        if (currencies == null || rates == null) {
            throw new IllegalArgumentException("arrays must not be null");
        }
        String[] names = currencies.clone();
        double[] rs = rates.clone();
        if (names.length == 0 || names.length != rs.length) {
            throw new IllegalArgumentException("arrays empty or mismatched");
        }
        if (ttl <= 0) throw new IllegalArgumentException("ttl must be positive");
        int n = names.length;
        for (int i = 0; i < n; i++) {
            validateName(names[i]);
            validateRate(rs[i]);
        }
        // duplicate detection with local open-addressing table
        long capL = 2;
        while (capL < (long) n * 2) capL <<= 1;
        int cap = (int) capL;
        int[] slots = new int[cap];
        for (int i = 0; i < cap; i++) slots[i] = -1;
        for (int i = 0; i < n; i++) {
            int idx = hashOf(names[i], cap);
            while (slots[idx] != -1) {
                if (names[slots[idx]].equals(names[i])) {
                    throw new IllegalArgumentException("duplicate currency in batch");
                }
                idx = (idx + 1) & (cap - 1);
            }
            slots[idx] = i;
        }
        commit(names, rs, ttl);
    }

    private void commit(String[] names, double[] rs, long ttl) {
        synchronized (lock) {
            long now = clock.now();
            if (ttl > Long.MAX_VALUE - now) {
                throw new ArithmeticException("expiration overflow");
            }
            if (revision == Long.MAX_VALUE) {
                throw new ArithmeticException("revision overflow");
            }
            long expiration = now + ttl;
            // count new keys for a single resize (no failure points after this)
            int added = 0;
            for (int i = 0; i < names.length; i++) {
                if (find(names[i]) == null) added++;
            }
            resizeIfNeeded(added);
            for (int i = 0; i < names.length; i++) {
                Entry e = new Entry(rs[i], expiration);
                Node node = find(names[i]);
                if (node != null) {
                    node.entry = e;
                } else {
                    int idx = hashOf(names[i], table.length);
                    table[idx] = new Node(names[i], e, table[idx]);
                    size++;
                }
            }
            revision++;
        }
    }

    public Conversion convert(String from, String to, double amount) {
        validateName(from);
        validateName(to);
        if (Double.isNaN(amount) || Double.isInfinite(amount) || amount < 0.0) {
            throw new IllegalArgumentException("invalid amount");
        }
        Entry fe;
        Entry te;
        long rev;
        long now;
        synchronized (lock) {
            now = clock.now();
            rev = revision;
            Node fn = find(from);
            Node tn = find(to);
            if (fn == null || fn.entry.expiration <= now) {
                throw new IllegalStateException("missing or expired rate: " + from);
            }
            if (tn == null || tn.entry.expiration <= now) {
                throw new IllegalStateException("missing or expired rate: " + to);
            }
            fe = fn.entry;
            te = tn.entry;
        }
        double value = amount / fe.rate * te.rate;
        return new Conversion(value, rev, now);
    }

    public int prune() {
        synchronized (lock) {
            long now = clock.now();
            int count = 0;
            for (int i = 0; i < table.length; i++) {
                for (Node n = table[i]; n != null; n = n.next) {
                    if (n.entry.expiration <= now) count++;
                }
            }
            if (count == 0) return 0;
            if (revision == Long.MAX_VALUE) {
                throw new ArithmeticException("revision overflow");
            }
            for (int i = 0; i < table.length; i++) {
                Node head = null;
                Node tail = null;
                Node n = table[i];
                while (n != null) {
                    Node next = n.next;
                    if (n.entry.expiration > now) {
                        n.next = null;
                        if (tail == null) head = n; else tail.next = n;
                        tail = n;
                    }
                    n = next;
                }
                table[i] = head;
            }
            size -= count;
            revision++;
            return count;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            long now = clock.now();
            RateEntry[] arr = new RateEntry[size];
            int k = 0;
            for (int i = 0; i < table.length; i++) {
                for (Node n = table[i]; n != null; n = n.next) {
                    arr[k++] = new RateEntry(n.key, n.entry.rate, n.entry.expiration);
                }
            }
            return new Snapshot(arr, revision, now);
        }
    }
}

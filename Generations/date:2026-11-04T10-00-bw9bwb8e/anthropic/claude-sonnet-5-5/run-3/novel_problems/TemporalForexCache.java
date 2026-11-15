import java.lang.Math;

@TemporalForexCache.ThreadSafe
public class TemporalForexCache {
    @interface ThreadSafe {}


    public interface Clock {
        long now();
    }

    /** Immutable rate record. */
    public static final class Entry {
        private final String currency;
        private final double rate;
        private final long expiration;

        Entry(String currency, double rate, long expiration) {
            this.currency = currency;
            this.rate = rate;
            this.expiration = expiration;
        }

        public String currency() { return currency; }
        public double rate() { return rate; }
        public long expiration() { return expiration; }
    }

    /** Immutable detached conversion result. */
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
    }

    /** Immutable detached snapshot. */
    public static final class Snapshot {
        private final Entry[] entries;
        private final long revision;
        private final long observationTime;

        Snapshot(Entry[] entries, long revision, long observationTime) {
            this.entries = entries;
            this.revision = revision;
            this.observationTime = observationTime;
        }

        public Entry[] entries() { return entries.clone(); }
        public long revision() { return revision; }
        public long observationTime() { return observationTime; }
    }

    private static final class Node {
        final String key;
        Entry entry;
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
    private Node[] buckets = new Node[16];
    private int size = 0;
    private long revision = 0;

    public TemporalForexCache(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.clock = clock;
    }

    public TemporalForexCache() {
        this(new Clock() {
            @Override
            public long now() {
                return System.currentTimeMillis();
            }
        });
    }

    private static void checkName(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("invalid currency name");
        }
    }

    private static void checkRate(double rate) {
        if (!(rate > 0.0) || Double.isNaN(rate) || Double.isInfinite(rate)) {
            throw new IllegalArgumentException("invalid rate");
        }
    }

    private static int indexFor(String key, int length) {
        int h = key.hashCode();
        h ^= (h >>> 16);
        return h & (length - 1);
    }

    // Must hold lock
    private Entry find(String key) {
        Node n = buckets[indexFor(key, buckets.length)];
        while (n != null) {
            if (n.key.equals(key)) {
                return n.entry;
            }
            n = n.next;
        }
        return null;
    }

    // Must hold lock
    private void put(String key, Entry e) {
        int idx = indexFor(key, buckets.length);
        Node n = buckets[idx];
        while (n != null) {
            if (n.key.equals(key)) {
                n.entry = e;
                return;
            }
            n = n.next;
        }
        buckets[idx] = new Node(key, e, buckets[idx]);
        size++;
        if (size > buckets.length - (buckets.length >> 2)) {
            resize();
        }
    }

    private void resize() {
        Node[] old = buckets;
        Node[] nb = new Node[old.length * 2];
        for (int i = 0; i < old.length; i++) {
            Node n = old[i];
            while (n != null) {
                Node next = n.next;
                int idx = indexFor(n.key, nb.length);
                n.next = nb[idx];
                nb[idx] = n;
                n = next;
            }
        }
        buckets = nb;
    }

    public void updateRate(String currency, double rate, long ttl) {
        checkName(currency);
        checkRate(rate);
        if (ttl <= 0) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        synchronized (lock) {
            long now = clock.now();
            if (ttl > Long.MAX_VALUE - now) {
                throw new ArithmeticException("expiration overflow");
            }
            if (revision == Long.MAX_VALUE) {
                throw new ArithmeticException("revision overflow");
            }
            put(currency, new Entry(currency, rate, now + ttl));
            revision++;
        }
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
        if (ttl <= 0) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        int n = names.length;
        for (int i = 0; i < n; i++) {
            checkName(names[i]);
            checkRate(rs[i]);
        }
        // duplicate detection with local open-addressing table
        int cap = 4;
        while (cap < n * 2) {
            cap <<= 1;
        }
        int[] slots = new int[cap];
        for (int i = 0; i < n; i++) {
            int idx = indexFor(names[i], cap);
            while (slots[idx] != 0) {
                if (names[slots[idx] - 1].equals(names[i])) {
                    throw new IllegalArgumentException("duplicate currency in batch");
                }
                idx = (idx + 1) & (cap - 1);
            }
            slots[idx] = i + 1;
        }
        synchronized (lock) {
            long now = clock.now();
            if (ttl > Long.MAX_VALUE - now) {
                throw new ArithmeticException("expiration overflow");
            }
            if (revision == Long.MAX_VALUE) {
                throw new ArithmeticException("revision overflow");
            }
            long exp = now + ttl;
            for (int i = 0; i < n; i++) {
                put(names[i], new Entry(names[i], rs[i], exp));
            }
            revision++;
        }
    }

    public Conversion convert(String from, String to, double amount) {
        checkName(from);
        checkName(to);
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
            fe = find(from);
            te = find(to);
        }
        if (fe == null || fe.expiration <= now) {
            throw new IllegalStateException("missing or expired rate: " + from);
        }
        if (te == null || te.expiration <= now) {
            throw new IllegalStateException("missing or expired rate: " + to);
        }
        double value = amount / fe.rate * te.rate;
        return new Conversion(value, rev, now);
    }

    public int prune() {
        synchronized (lock) {
            long now = clock.now();
            int count = 0;
            for (int i = 0; i < buckets.length; i++) {
                for (Node n = buckets[i]; n != null; n = n.next) {
                    if (n.entry.expiration <= now) {
                        count++;
                    }
                }
            }
            if (count == 0) {
                return 0;
            }
            if (revision == Long.MAX_VALUE) {
                throw new ArithmeticException("revision overflow");
            }
            for (int i = 0; i < buckets.length; i++) {
                Node prev = null;
                Node n = buckets[i];
                while (n != null) {
                    if (n.entry.expiration <= now) {
                        if (prev == null) {
                            buckets[i] = n.next;
                        } else {
                            prev.next = n.next;
                        }
                        size--;
                    } else {
                        prev = n;
                    }
                    n = n.next;
                }
            }
            revision++;
            return count;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            long now = clock.now();
            Entry[] out = new Entry[size];
            int k = 0;
            for (int i = 0; i < buckets.length; i++) {
                for (Node n = buckets[i]; n != null; n = n.next) {
                    out[k++] = n.entry; // immutable, safe to share
                }
            }
            return new Snapshot(out, revision, now);
        }
    }
}

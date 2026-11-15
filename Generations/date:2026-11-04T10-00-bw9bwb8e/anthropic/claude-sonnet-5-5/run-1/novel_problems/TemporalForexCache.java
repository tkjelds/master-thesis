import java.io.Serializable;

@TemporalForexCache.ThreadSafe
public class TemporalForexCache {
    @interface ThreadSafe {}


    /** Injected time source: thread-safe, nonnegative, monotonic, nonblocking. */
    public interface Clock {
        long now();
    }

    /** Detached conversion result. */
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

    /** Detached coherent snapshot. */
    public static final class Snapshot {
        private final String[] currencies;
        private final double[] rates;
        private final long[] expirations;
        private final long revision;
        private final long observationTime;

        Snapshot(String[] currencies, double[] rates, long[] expirations,
                 long revision, long observationTime) {
            this.currencies = currencies;
            this.rates = rates;
            this.expirations = expirations;
            this.revision = revision;
            this.observationTime = observationTime;
        }

        public int size() { return currencies.length; }
        public String[] currencies() { return currencies.clone(); }
        public double[] rates() { return rates.clone(); }
        public long[] expirations() { return expirations.clone(); }
        public long revision() { return revision; }
        public long observationTime() { return observationTime; }

        public boolean isValid(int index) {
            return expirations[index] > observationTime;
        }
    }

    /** Immutable rate record; all fields final for safe publication. */
    private static final class Rate {
        final double rate;
        final long expiration;

        Rate(double rate, long expiration) {
            this.rate = rate;
            this.expiration = expiration;
        }
    }

    /** Hash node; fields mutated only while holding the lock. */
    private static final class Node {
        final String currency;
        final int hash;
        Rate rate;
        Node next;

        Node(String currency, int hash, Rate rate, Node next) {
            this.currency = currency;
            this.hash = hash;
            this.rate = rate;
            this.next = next;
        }
    }

    private final Clock clock;
    private final Object lock = new Object();
    private Node[] table = new Node[16];   // guarded by lock
    private int size = 0;                  // guarded by lock
    private long revision = 0L;            // guarded by lock

    public TemporalForexCache(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.clock = clock;
    }

    // ---------- validation helpers ----------

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

    private static int spread(int h) {
        return h ^ (h >>> 16);
    }

    // ---------- updates ----------

    public void updateRate(String currency, double rate, long ttl) {
        checkName(currency);
        checkRate(rate);
        if (ttl <= 0) {
            throw new IllegalArgumentException("ttl must be positive");
        }
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
        if (ttl <= 0) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        int n = names.length;
        int cap = 4;
        while (cap < n * 2) {
            cap <<= 1;
        }
        int[] seen = new int[cap]; // stores index+1
        for (int i = 0; i < n; i++) {
            checkName(names[i]);
            checkRate(rs[i]);
            int pos = spread(names[i].hashCode()) & (cap - 1);
            while (seen[pos] != 0) {
                if (names[seen[pos] - 1].equals(names[i])) {
                    throw new IllegalArgumentException("duplicate currency in batch");
                }
                pos = (pos + 1) & (cap - 1);
            }
            seen[pos] = i + 1;
        }
        commit(names, rs, ttl);
    }

    private void commit(String[] names, double[] rs, long ttl) {
        synchronized (lock) {
            long now = clock.now();
            if (now > Long.MAX_VALUE - ttl) {
                throw new ArithmeticException("expiration overflow");
            }
            if (revision == Long.MAX_VALUE) {
                throw new ArithmeticException("revision overflow");
            }
            long expiration = now + ttl;
            ensureCapacity(size + names.length);
            for (int i = 0; i < names.length; i++) {
                put(names[i], new Rate(rs[i], expiration));
            }
            revision++;
        }
    }

    // ---------- table internals (call under lock) ----------

    private void ensureCapacity(int needed) {
        int cap = table.length;
        if (needed <= cap * 3 / 4) {
            return;
        }
        while (needed > cap * 3 / 4) {
            cap <<= 1;
        }
        Node[] nt = new Node[cap];
        for (int i = 0; i < table.length; i++) {
            Node n = table[i];
            while (n != null) {
                Node next = n.next;
                int idx = n.hash & (cap - 1);
                n.next = nt[idx];
                nt[idx] = n;
                n = next;
            }
        }
        table = nt;
    }

    private Node find(String currency) {
        int h = spread(currency.hashCode());
        Node n = table[h & (table.length - 1)];
        while (n != null) {
            if (n.hash == h && n.currency.equals(currency)) {
                return n;
            }
            n = n.next;
        }
        return null;
    }

    private void put(String currency, Rate rate) {
        Node n = find(currency);
        if (n != null) {
            n.rate = rate;
            return;
        }
        int h = spread(currency.hashCode());
        int idx = h & (table.length - 1);
        table[idx] = new Node(currency, h, rate, table[idx]);
        size++;
    }

    // ---------- convert ----------

    public Conversion convert(String from, String to, double amount) {
        checkName(from);
        checkName(to);
        if (Double.isNaN(amount) || Double.isInfinite(amount) || amount < 0.0) {
            throw new IllegalArgumentException("invalid amount");
        }
        Rate fromRate;
        Rate toRate;
        long rev;
        long now;
        synchronized (lock) {
            now = clock.now();
            Node f = find(from);
            Node t = find(to);
            if (f == null || t == null) {
                throw new IllegalStateException("missing rate");
            }
            fromRate = f.rate;
            toRate = t.rate;
            if (fromRate.expiration <= now || toRate.expiration <= now) {
                throw new IllegalStateException("expired rate");
            }
            rev = revision;
        }
        double value = amount / fromRate.rate * toRate.rate;
        return new Conversion(value, rev, now);
    }

    // ---------- prune ----------

    public int prune() {
        synchronized (lock) {
            long now = clock.now();
            int count = 0;
            for (int i = 0; i < table.length; i++) {
                for (Node n = table[i]; n != null; n = n.next) {
                    if (n.rate.expiration <= now) {
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
            for (int i = 0; i < table.length; i++) {
                Node prev = null;
                Node n = table[i];
                while (n != null) {
                    Node next = n.next;
                    if (n.rate.expiration <= now) {
                        if (prev == null) {
                            table[i] = next;
                        } else {
                            prev.next = next;
                        }
                        size--;
                    } else {
                        prev = n;
                    }
                    n = next;
                }
            }
            revision++;
            return count;
        }
    }

    // ---------- snapshot ----------

    public Snapshot snapshot() {
        synchronized (lock) {
            long now = clock.now();
            String[] names = new String[size];
            double[] rs = new double[size];
            long[] exps = new long[size];
            int k = 0;
            for (int i = 0; i < table.length; i++) {
                for (Node n = table[i]; n != null; n = n.next) {
                    names[k] = n.currency;
                    rs[k] = n.rate.rate;
                    exps[k] = n.rate.expiration;
                    k++;
                }
            }
            return new Snapshot(names, rs, exps, revision, now);
        }
    }
}

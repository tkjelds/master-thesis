@TemporalForexCache.ThreadSafe
public final class TemporalForexCache {
    @interface ThreadSafe {}


    /**
     * Injected time source. Implementations must be thread-safe, nonnegative,
     * monotonic, and nonblocking.
     */
    public interface Clock {
        long now();
    }

    /**
     * Detached result of a conversion.
     */
    public static final class Conversion {
        private final double value;
        private final long revision;
        private final long observationTime;

        Conversion(double value, long revision, long observationTime) {
            this.value = value;
            this.revision = revision;
            this.observationTime = observationTime;
        }

        public double getValue() {
            return value;
        }

        public long getRevision() {
            return revision;
        }

        public long getObservationTime() {
            return observationTime;
        }
    }

    /**
     * Detached, coherent copy of the cache contents at one revision and observation time.
     * Includes all stored entries (expired ones too, distinguishable by expiration).
     */
    public static final class Snapshot {
        private final long revision;
        private final long observationTime;
        private final String[] currencies;
        private final double[] rates;
        private final long[] expirations;

        Snapshot(long revision, long observationTime, String[] currencies, double[] rates, long[] expirations) {
            this.revision = revision;
            this.observationTime = observationTime;
            this.currencies = currencies;
            this.rates = rates;
            this.expirations = expirations;
        }

        public long getRevision() {
            return revision;
        }

        public long getObservationTime() {
            return observationTime;
        }

        public int size() {
            return currencies.length;
        }

        public String getCurrency(int index) {
            return currencies[index];
        }

        public double getRate(int index) {
            return rates[index];
        }

        public long getExpiration(int index) {
            return expirations[index];
        }

        public String[] getCurrencies() {
            return currencies.clone();
        }

        public double[] getRates() {
            return rates.clone();
        }

        public long[] getExpirations() {
            return expirations.clone();
        }
    }

    /** Immutable rate record: value and expiration are set once, before publication under the lock. */
    private static final class Rate {
        final double rate;
        final long expiresAt;

        Rate(double rate, long expiresAt) {
            this.rate = rate;
            this.expiresAt = expiresAt;
        }
    }

    /** Hash table node; only accessed while holding the lock. */
    private static final class Node {
        final String key;
        Rate value;
        Node next;

        Node(String key, Rate value, Node next) {
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }

    private static final int INITIAL_CAPACITY = 16;

    private final Clock clock;
    // Policy: all operations are nonblocking and fail fast. Lock is held only for
    // short, bounded, non-blocking critical sections (no waiting is ever needed).
    private final Object lock = new Object();

    // All fields below are guarded by lock.
    private Node[] buckets = new Node[INITIAL_CAPACITY];
    private int size;
    private long revision;

    public TemporalForexCache(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.clock = clock;
    }

    // ---------------------------------------------------------------- public API

    /**
     * Atomically replaces one rate and its expiration; advances revision once.
     */
    public void updateRate(String currency, double rate, long ttl) {
        requireName(currency);
        requireRate(rate);
        requireTtl(ttl);

        synchronized (lock) {
            long t = clock.now();
            long exp = expirationFor(t, ttl);
            requireRevisionCapacity();
            put(currency, new Rate(rate, exp));
            revision++;
        }
    }

    /**
     * Atomically applies a whole batch at one observation time; advances revision once.
     * The batch is copied and fully validated before any mutation.
     */
    public void updateRatesBatch(String[] currencies, double[] rates, long ttl) {
        if (currencies == null || rates == null) {
            throw new IllegalArgumentException("arrays must not be null");
        }
        // Copy inputs so caller mutations cannot affect validation or application.
        String[] names = currencies.clone();
        double[] values = rates.clone();

        if (names.length == 0) {
            throw new IllegalArgumentException("batch must not be empty");
        }
        if (names.length != values.length) {
            throw new IllegalArgumentException("array length mismatch");
        }
        requireTtl(ttl);
        for (int i = 0; i < names.length; i++) {
            requireName(names[i]);
            requireRate(values[i]);
            for (int j = 0; j < i; j++) {
                if (names[j].equals(names[i])) {
                    throw new IllegalArgumentException("duplicate currency in batch: " + names[i]);
                }
            }
        }

        synchronized (lock) {
            long t = clock.now();
            // Validate expirations and revision before any mutation.
            long exp = expirationFor(t, ttl);
            requireRevisionCapacity();

            for (int i = 0; i < names.length; i++) {
                put(names[i], new Rate(values[i], exp));
            }
            revision++;
        }
    }

    /**
     * Converts amount from one currency to another using valid (unexpired) rates
     * captured under one lock acquisition. Throws IllegalStateException if either
     * rate is missing or expired (including same-currency conversion).
     */
    public Conversion convert(String from, String to, double amount) {
        requireName(from);
        requireName(to);
        requireAmount(amount);

        Rate fromRate;
        Rate toRate;
        long rev;
        long t;

        synchronized (lock) {
            t = clock.now();
            fromRate = validRate(from, t);
            toRate = validRate(to, t);
            if (fromRate == null) {
                throw new IllegalStateException("no valid rate for currency: " + from);
            }
            if (toRate == null) {
                throw new IllegalStateException("no valid rate for currency: " + to);
            }
            rev = revision;
        }

        // Computed outside the monitor; Rate objects are immutable.
        double value = amount / fromRate.rate * toRate.rate;
        return new Conversion(value, rev, t);
    }

    /**
     * Removes expired rates at one captured time. Returns number removed.
     * Advances revision exactly once only if removal occurs.
     */
    public int prune() {
        synchronized (lock) {
            long t = clock.now();

            int expired = 0;
            for (int b = 0; b < buckets.length; b++) {
                for (Node n = buckets[b]; n != null; n = n.next) {
                    if (n.value.expiresAt <= t) {
                        expired++;
                    }
                }
            }
            if (expired == 0) {
                return 0;
            }
            requireRevisionCapacity();

            int removed = 0;
            for (int b = 0; b < buckets.length; b++) {
                Node prev = null;
                Node n = buckets[b];
                while (n != null) {
                    Node next = n.next;
                    if (n.value.expiresAt <= t) {
                        if (prev == null) {
                            buckets[b] = next;
                        } else {
                            prev.next = next;
                        }
                        size--;
                        removed++;
                    } else {
                        prev = n;
                    }
                    n = next;
                }
            }
            revision++;
            return removed;
        }
    }

    /**
     * Returns a detached, coherent copy of all entries with one revision and
     * observation time. Does not prune.
     */
    public Snapshot snapshot() {
        synchronized (lock) {
            long t = clock.now();
            String[] names = new String[size];
            double[] rates = new double[size];
            long[] exps = new long[size];
            int idx = 0;
            for (int b = 0; b < buckets.length; b++) {
                for (Node n = buckets[b]; n != null; n = n.next) {
                    names[idx] = n.key;
                    rates[idx] = n.value.rate;
                    exps[idx] = n.value.expiresAt;
                    idx++;
                }
            }
            return new Snapshot(revision, t, names, rates, exps);
        }
    }

    // ---------------------------------------------------------- guarded helpers
    // The following helpers must only be called while holding lock.

    private Rate validRate(String currency, long t) {
        Node n = find(currency);
        if (n == null || n.value.expiresAt <= t) {
            return null;
        }
        return n.value;
    }

    private Node find(String key) {
        int idx = indexFor(key, buckets.length);
        for (Node n = buckets[idx]; n != null; n = n.next) {
            if (n.key.equals(key)) {
                return n;
            }
        }
        return null;
    }

    private void put(String key, Rate value) {
        Node existing = find(key);
        if (existing != null) {
            existing.value = value;
            return;
        }
        int idx = indexFor(key, buckets.length);
        buckets[idx] = new Node(key, value, buckets[idx]);
        size++;
        if (size > buckets.length * 3 / 4) {
            resize(buckets.length * 2);
        }
    }

    private void resize(int newCapacity) {
        Node[] newBuckets = new Node[newCapacity];
        for (int b = 0; b < buckets.length; b++) {
            Node n = buckets[b];
            while (n != null) {
                Node next = n.next;
                int idx = indexFor(n.key, newCapacity);
                n.next = newBuckets[idx];
                newBuckets[idx] = n;
                n = next;
            }
        }
        buckets = newBuckets;
    }

    private void requireRevisionCapacity() {
        if (revision == Long.MAX_VALUE) {
            throw new IllegalStateException("revision overflow");
        }
    }

    private static long expirationFor(long now, long ttl) {
        if (ttl > Long.MAX_VALUE - now) {
            throw new IllegalArgumentException("expiration overflow");
        }
        return now + ttl;
    }

    private static int indexFor(String key, int capacity) {
        int h = key.hashCode();
        h ^= (h >>> 16);
        return (h & 0x7fffffff) % capacity;
    }

    // ---------------------------------------------------------- validation

    private static void requireName(String currency) {
        if (currency == null || currency.isEmpty()) {
            throw new IllegalArgumentException("currency name must be non-null and non-empty");
        }
    }

    private static void requireRate(double rate) {
        if (Double.isNaN(rate) || Double.isInfinite(rate) || rate <= 0.0) {
            throw new IllegalArgumentException("rate must be positive and finite");
        }
    }

    private static void requireAmount(double amount) {
        if (Double.isNaN(amount) || Double.isInfinite(amount) || amount < 0.0) {
            throw new IllegalArgumentException("amount must be nonnegative and finite");
        }
    }

    private static void requireTtl(long ttl) {
        if (ttl <= 0L) {
            throw new IllegalArgumentException("ttl must be positive");
        }
    }
}

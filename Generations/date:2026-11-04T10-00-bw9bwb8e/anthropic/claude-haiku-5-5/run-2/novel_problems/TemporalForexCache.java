@TemporalForexCache.ThreadSafe
public final class TemporalForexCache {
    @interface ThreadSafe {}


    /** Injected time source. Implementations must be thread-safe, nonnegative, monotonic, and nonblocking. */
    public interface Clock {
        long now();
    }

    /** Immutable rate record. All fields are final, so instances are safely published. */
    public static final class RateEntry {
        private final String currency;
        private final double rate;
        private final long expiration;

        RateEntry(String currency, double rate, long expiration) {
            this.currency = currency;
            this.rate = rate;
            this.expiration = expiration;
        }

        public String getCurrency() {
            return currency;
        }

        public double getRate() {
            return rate;
        }

        public long getExpiration() {
            return expiration;
        }
    }

    /** Detached result of a conversion. */
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

    /** Detached, coherent snapshot of the cache. */
    public static final class Snapshot {
        private final long revision;
        private final long observationTime;
        private final RateEntry[] entries;

        Snapshot(long revision, long observationTime, RateEntry[] entries) {
            this.revision = revision;
            this.observationTime = observationTime;
            this.entries = entries;
        }

        public long getRevision() {
            return revision;
        }

        public long getObservationTime() {
            return observationTime;
        }

        /** Returns a fresh copy of the entries array. */
        public RateEntry[] getEntries() {
            RateEntry[] copy = new RateEntry[entries.length];
            System.arraycopy(entries, 0, copy, 0, entries.length);
            return copy;
        }
    }

    // Blocking policy: no operation waits. All operations take a short monitor
    // critical section, and invalid input or missing/expired rates are reported
    // immediately with exceptions. Unbounded growth is limited only by memory.

    private final Clock clock;
    private final Object lock = new Object();
    // Guarded by lock.
    private RateEntry[] entries = new RateEntry[16];
    private int count;
    private long revision;

    public TemporalForexCache(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.clock = clock;
    }

    public void updateRate(String currency, double rate, long ttl) {
        validateCurrency(currency);
        validateRate(rate);
        if (ttl <= 0) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        synchronized (lock) {
            long now = clock.now();
            long expiration = computeExpiration(now, ttl);
            long nextRevision = nextRevision();
            int idx = indexOf(currency);
            RateEntry e = new RateEntry(currency, rate, expiration);
            if (idx >= 0) {
                entries[idx] = e;
            } else {
                append(e);
            }
            revision = nextRevision;
        }
    }

    public void updateRatesBatch(String[] currencies, double[] rates, long ttl) {
        if (currencies == null || rates == null) {
            throw new IllegalArgumentException("arrays must not be null");
        }
        if (currencies.length == 0 || rates.length == 0) {
            throw new IllegalArgumentException("batch must not be empty");
        }
        if (currencies.length != rates.length) {
            throw new IllegalArgumentException("array lengths must match");
        }
        if (ttl <= 0) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        // Copy inputs before validation so callers cannot mutate them concurrently.
        String[] names = new String[currencies.length];
        double[] values = new double[rates.length];
        for (int i = 0; i < names.length; i++) {
            names[i] = currencies[i];
            values[i] = rates[i];
        }
        // Validate entire batch before any mutation.
        for (int i = 0; i < names.length; i++) {
            validateCurrency(names[i]);
            validateRate(values[i]);
            for (int j = 0; j < i; j++) {
                if (names[j].equals(names[i])) {
                    throw new IllegalArgumentException("duplicate currency in batch: " + names[i]);
                }
            }
        }
        synchronized (lock) {
            long now = clock.now();
            long expiration = computeExpiration(now, ttl);
            long nextRevision = nextRevision();
            for (int i = 0; i < names.length; i++) {
                RateEntry e = new RateEntry(names[i], values[i], expiration);
                int idx = indexOf(names[i]);
                if (idx >= 0) {
                    entries[idx] = e;
                } else {
                    append(e);
                }
            }
            revision = nextRevision;
        }
    }

    public Conversion convert(String from, String to, double amount) {
        validateCurrency(from);
        validateCurrency(to);
        if (Double.isNaN(amount) || Double.isInfinite(amount) || amount < 0.0) {
            throw new IllegalArgumentException("amount must be finite and nonnegative");
        }
        double fromRate;
        double toRate;
        long rev;
        long observation;
        synchronized (lock) {
            observation = clock.now();
            int fi = indexOf(from);
            int ti = indexOf(to);
            if (fi < 0 || entries[fi].expiration <= observation) {
                throw new IllegalStateException("missing or expired rate: " + from);
            }
            if (ti < 0 || entries[ti].expiration <= observation) {
                throw new IllegalStateException("missing or expired rate: " + to);
            }
            fromRate = entries[fi].rate;
            toRate = entries[ti].rate;
            rev = revision;
        }
        double value = amount / fromRate * toRate;
        return new Conversion(value, rev, observation);
    }

    public int prune() {
        synchronized (lock) {
            long now = clock.now();
            int removed = 0;
            for (int i = 0; i < count; i++) {
                if (entries[i].expiration <= now) {
                    removed++;
                }
            }
            if (removed == 0) {
                return 0;
            }
            long nextRevision = nextRevision();
            int w = 0;
            for (int r = 0; r < count; r++) {
                if (entries[r].expiration > now) {
                    entries[w++] = entries[r];
                }
            }
            for (int i = w; i < count; i++) {
                entries[i] = null;
            }
            count = w;
            revision = nextRevision;
            return removed;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            long now = clock.now();
            RateEntry[] copy = new RateEntry[count];
            System.arraycopy(entries, 0, copy, 0, count);
            return new Snapshot(revision, now, copy);
        }
    }

    // ----- helpers; callers hold lock where noted -----

    private static void validateCurrency(String currency) {
        if (currency == null || currency.isEmpty()) {
            throw new IllegalArgumentException("currency name must be non-null and non-empty");
        }
    }

    private static void validateRate(double rate) {
        if (!(rate > 0.0) || Double.isInfinite(rate)) {
            throw new IllegalArgumentException("rate must be positive and finite");
        }
    }

    private static long computeExpiration(long now, long ttl) {
        if (ttl > Long.MAX_VALUE - now) {
            throw new IllegalArgumentException("expiration overflow");
        }
        return now + ttl;
    }

    /** Must be called under lock. Returns the next revision without mutating state. */
    private long nextRevision() {
        if (revision == Long.MAX_VALUE) {
            throw new IllegalStateException("revision overflow");
        }
        return revision + 1;
    }

    /** Must be called under lock. */
    private int indexOf(String currency) {
        for (int i = 0; i < count; i++) {
            if (entries[i].currency.equals(currency)) {
                return i;
            }
        }
        return -1;
    }

    /** Must be called under lock. */
    private void append(RateEntry e) {
        if (count == entries.length) {
            RateEntry[] grown = new RateEntry[entries.length * 2];
            System.arraycopy(entries, 0, grown, 0, count);
            entries = grown;
        }
        entries[count++] = e;
    }
}

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

    /** Immutable rate record. All fields are final and set before publication under the monitor. */
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

    /** Detached, coherent view of the cache. */
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

        /** Returns a copy of the entries array; RateEntry objects are immutable. */
        public RateEntry[] getEntries() {
            RateEntry[] copy = new RateEntry[entries.length];
            System.arraycopy(entries, 0, copy, 0, entries.length);
            return copy;
        }
    }

    // Policy: all operations are non-blocking. Invalid arguments are rejected with
    // IllegalArgumentException; missing or expired rates in convert() fail fast with
    // IllegalStateException; revision/expiration overflow fails fast before mutation.
    // There is no bounded capacity, so no operation waits.

    private final Clock clock;
    private final Object lock = new Object();

    // Guarded by lock.
    private RateEntry[] entries = new RateEntry[8];
    private int count;
    private long revision;

    public TemporalForexCache(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.clock = clock;
    }

    public void updateRate(String currency, double rate, long ttl) {
        updateRatesBatch(new String[] {currency}, new double[] {rate}, ttl);
    }

    public void updateRatesBatch(String[] currencies, double[] rates, long ttl) {
        if (currencies == null || rates == null) {
            throw new IllegalArgumentException("arrays must not be null");
        }
        if (currencies.length == 0 || currencies.length != rates.length) {
            throw new IllegalArgumentException("arrays must be nonempty and of equal length");
        }
        if (ttl <= 0) {
            throw new IllegalArgumentException("ttl must be positive");
        }

        // Copy inputs so caller mutation cannot affect validation or application.
        String[] names = currencies.clone();
        double[] values = rates.clone();

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
            // All checks happen before any mutation.
            long now = clock.now();
            long expiration = expirationFor(now, ttl);
            checkRevisionIncrement();

            int newCount = count;
            for (int i = 0; i < names.length; i++) {
                if (indexOf(names[i]) < 0) {
                    newCount++;
                }
            }
            ensureCapacity(newCount);

            for (int i = 0; i < names.length; i++) {
                int idx = indexOf(names[i]);
                if (idx < 0) {
                    idx = count++;
                }
                entries[idx] = new RateEntry(names[i], values[i], expiration);
            }
            revision++;
        }
    }

    public Conversion convert(String from, String to, double amount) {
        validateCurrency(from);
        validateCurrency(to);
        validateAmount(amount);

        RateEntry fromEntry;
        RateEntry toEntry;
        long rev;
        long obs;

        synchronized (lock) {
            obs = clock.now();
            rev = revision;

            int fi = indexOf(from);
            if (fi < 0 || !(entries[fi].expiration > obs)) {
                throw new IllegalStateException("no valid rate for currency: " + from);
            }
            int ti = indexOf(to);
            if (ti < 0 || !(entries[ti].expiration > obs)) {
                throw new IllegalStateException("no valid rate for currency: " + to);
            }
            fromEntry = entries[fi];
            toEntry = entries[ti];
        }

        // Computed outside the monitor using immutable captured values.
        double value = amount / fromEntry.rate * toEntry.rate;
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new ArithmeticException("conversion result is not finite");
        }
        return new Conversion(value, rev, obs);
    }

    public int prune() {
        synchronized (lock) {
            long now = clock.now();

            int removed = 0;
            for (int i = 0; i < count; i++) {
                if (!(entries[i].expiration > now)) {
                    removed++;
                }
            }
            if (removed == 0) {
                return 0;
            }

            checkRevisionIncrement();

            int newCount = 0;
            for (int i = 0; i < count; i++) {
                RateEntry e = entries[i];
                if (e.expiration > now) {
                    entries[newCount++] = e;
                }
            }
            for (int i = newCount; i < count; i++) {
                entries[i] = null;
            }
            count = newCount;
            revision++;
            return removed;
        }
    }

    /** Returns all stored entries (including expired ones; no pruning is performed). */
    public Snapshot snapshot() {
        synchronized (lock) {
            long now = clock.now();
            RateEntry[] copy = new RateEntry[count];
            System.arraycopy(entries, 0, copy, 0, count);
            return new Snapshot(revision, now, copy);
        }
    }

    // ---- Helpers. Methods below that touch state must be called while holding lock. ----

    private int indexOf(String currency) {
        for (int i = 0; i < count; i++) {
            if (entries[i].currency.equals(currency)) {
                return i;
            }
        }
        return -1;
    }

    private void ensureCapacity(int required) {
        if (required > entries.length) {
            int newLength = entries.length;
            while (newLength < required) {
                newLength = newLength * 2;
            }
            RateEntry[] bigger = new RateEntry[newLength];
            System.arraycopy(entries, 0, bigger, 0, count);
            entries = bigger;
        }
    }

    private void checkRevisionIncrement() {
        if (revision == Long.MAX_VALUE) {
            throw new IllegalStateException("cache revision overflow");
        }
    }

    private static long expirationFor(long now, long ttl) {
        if (ttl > Long.MAX_VALUE - now) {
            throw new ArithmeticException("expiration overflow");
        }
        return now + ttl;
    }

    private static void validateCurrency(String currency) {
        if (currency == null || currency.isEmpty()) {
            throw new IllegalArgumentException("currency name must be nonnull and nonempty");
        }
    }

    private static void validateRate(double rate) {
        if (Double.isNaN(rate) || Double.isInfinite(rate) || rate <= 0.0) {
            throw new IllegalArgumentException("rate must be positive and finite");
        }
    }

    private static void validateAmount(double amount) {
        if (Double.isNaN(amount) || Double.isInfinite(amount) || amount < 0.0) {
            throw new IllegalArgumentException("amount must be nonnegative and finite");
        }
    }
}

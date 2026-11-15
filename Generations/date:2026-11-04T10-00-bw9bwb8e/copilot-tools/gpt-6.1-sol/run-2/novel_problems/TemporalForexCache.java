@TemporalForexCache.ThreadSafe
public final class TemporalForexCache {
    @interface ThreadSafe {}

    public interface Clock {
        long now();
    }

    public record Conversion(
            double value, long revision, long observationTime) {
    }

    public record Entry(String currency, double rate, long expiration) {
    }

    public static final class Snapshot {
        private final Entry[] entries;
        private final long revision;
        private final long observationTime;

        private Snapshot(Entry[] entries, long revision, long observationTime) {
            this.entries = copyEntries(entries);
            this.revision = revision;
            this.observationTime = observationTime;
        }

        public Entry[] entries() {
            return copyEntries(entries);
        }

        public long revision() {
            return revision;
        }

        public long observationTime() {
            return observationTime;
        }
    }

    private static final long MAX_REVISION = 9223372036854775807L;
    private static final double MAX_FINITE_DOUBLE = 0x1.fffffffffffffp1023;

    private final Object monitor = new Object();
    private final Clock clock;
    private Entry[] entries = new Entry[0];
    private long revision;

    public TemporalForexCache(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("Clock must not be null");
        }
        this.clock = clock;
    }

    // Storage grows as needed; missing or expired rates fail without waiting.
    public void updateRate(String currency, double rate, long ttl) {
        updateRatesBatch(new String[] { currency }, new double[] { rate }, ttl);
    }

    public void updateRatesBatch(
            String[] currencies, double[] rates, long ttl) {
        if (currencies == null || rates == null) {
            throw new IllegalArgumentException("Batch arrays must not be null");
        }
        if (currencies.length == 0 || currencies.length != rates.length) {
            throw new IllegalArgumentException(
                    "Batch arrays must have equal, nonzero lengths");
        }
        if (ttl <= 0) {
            throw new IllegalArgumentException("TTL must be positive");
        }

        String[] namesCopy = new String[currencies.length];
        double[] ratesCopy = new double[rates.length];
        for (int i = 0; i < namesCopy.length; i++) {
            namesCopy[i] = currencies[i];
            ratesCopy[i] = rates[i];
        }

        for (int i = 0; i < namesCopy.length; i++) {
            validateCurrency(namesCopy[i]);
            if (!(ratesCopy[i] > 0.0
                    && ratesCopy[i] <= MAX_FINITE_DOUBLE)) {
                throw new IllegalArgumentException(
                        "Rates must be positive and finite");
            }
            for (int j = 0; j < i; j++) {
                if (namesCopy[i].equals(namesCopy[j])) {
                    throw new IllegalArgumentException(
                            "Duplicate currency: " + namesCopy[i]);
                }
            }
        }

        synchronized (monitor) {
            requireRevisionAvailable();
            long observationTime = clock.now();
            if (ttl > MAX_REVISION - observationTime) {
                throw new IllegalArgumentException("Expiration overflow");
            }
            long expiration = observationTime + ttl;

            int additional = 0;
            for (int i = 0; i < namesCopy.length; i++) {
                if (find(entries, namesCopy[i]) < 0) {
                    additional++;
                }
            }
            if (additional > 2147483647 - entries.length) {
                throw new IllegalStateException("Cache capacity overflow");
            }

            Entry[] replacement = new Entry[entries.length + additional];
            for (int i = 0; i < entries.length; i++) {
                replacement[i] = entries[i];
            }

            int next = entries.length;
            for (int i = 0; i < namesCopy.length; i++) {
                int index = find(entries, namesCopy[i]);
                if (index < 0) {
                    index = next++;
                }
                replacement[index] =
                        new Entry(namesCopy[i], ratesCopy[i], expiration);
            }

            entries = replacement;
            revision++;
        }
    }

    public Conversion convert(String from, String to, double amount) {
        validateCurrency(from);
        validateCurrency(to);
        if (!(amount >= 0.0 && amount <= MAX_FINITE_DOUBLE)) {
            throw new IllegalArgumentException(
                    "Amount must be nonnegative and finite");
        }

        final Entry fromEntry;
        final Entry toEntry;
        final long capturedRevision;
        final long observationTime;

        synchronized (monitor) {
            observationTime = clock.now();
            int fromIndex = find(entries, from);
            int toIndex = find(entries, to);
            if (fromIndex < 0 || toIndex < 0) {
                throw new IllegalStateException("Missing currency rate");
            }

            fromEntry = entries[fromIndex];
            toEntry = entries[toIndex];
            if (fromEntry.expiration() <= observationTime
                    || toEntry.expiration() <= observationTime) {
                throw new IllegalStateException("Expired currency rate");
            }
            capturedRevision = revision;
        }

        double value = amount / fromEntry.rate() * toEntry.rate();
        return new Conversion(value, capturedRevision, observationTime);
    }

    public int prune() {
        synchronized (monitor) {
            long observationTime = clock.now();
            int removed = 0;
            for (int i = 0; i < entries.length; i++) {
                if (entries[i].expiration() <= observationTime) {
                    removed++;
                }
            }
            if (removed == 0) {
                return 0;
            }

            requireRevisionAvailable();
            Entry[] replacement = new Entry[entries.length - removed];
            int next = 0;
            for (int i = 0; i < entries.length; i++) {
                if (entries[i].expiration() > observationTime) {
                    replacement[next++] = entries[i];
                }
            }

            entries = replacement;
            revision++;
            return removed;
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            long observationTime = clock.now();
            return new Snapshot(entries, revision, observationTime);
        }
    }

    private void requireRevisionAvailable() {
        if (revision == MAX_REVISION) {
            throw new IllegalStateException("Cache revision overflow");
        }
    }

    private static void validateCurrency(String currency) {
        if (currency == null || currency.length() == 0) {
            throw new IllegalArgumentException(
                    "Currency must not be null or empty");
        }
    }

    private static int find(Entry[] entries, String currency) {
        for (int i = 0; i < entries.length; i++) {
            if (entries[i].currency().equals(currency)) {
                return i;
            }
        }
        return -1;
    }

    private static Entry[] copyEntries(Entry[] source) {
        Entry[] copy = new Entry[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }
}

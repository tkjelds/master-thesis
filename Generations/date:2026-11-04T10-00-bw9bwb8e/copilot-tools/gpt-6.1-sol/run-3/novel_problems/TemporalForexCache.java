@TemporalForexCache.ThreadSafe
public final class TemporalForexCache {
    @interface ThreadSafe {}

    private static final long MAX_LONG = 9223372036854775807L;
    private static final int MAX_ARRAY_LENGTH = 2147483647;
    private static final double MAX_FINITE_DOUBLE = 1.7976931348623157E308;

    public interface Clock {
        long now();
    }

    public record Conversion(
            double value,
            long revision,
            long observationTime) {
    }

    public record Entry(
            String currency,
            double rate,
            long expiration) {
    }

    public static final class Snapshot {
        private final Entry[] entries;
        private final long revision;
        private final long observationTime;

        private Snapshot(
                Entry[] entries, long revision, long observationTime) {
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

    private final Object monitor = new Object();
    private final Clock clock;

    // Accessed only while holding monitor; published entries are immutable.
    private Entry[] entries = new Entry[0];
    private long revision;

    public TemporalForexCache(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("Clock must not be null");
        }
        this.clock = clock;
    }

    public void updateRate(String currency, double rate, long ttl) {
        updateRatesBatch(
                new String[] {currency},
                new double[] {rate},
                ttl);
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

        int count = currencies.length;
        String[] namesCopy = new String[count];
        double[] ratesCopy = new double[count];

        for (int i = 0; i < count; i++) {
            namesCopy[i] = currencies[i];
            ratesCopy[i] = rates[i];
        }

        for (int i = 0; i < count; i++) {
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
            long observationTime = clock.now();
            if (observationTime > MAX_LONG - ttl) {
                throw new ArithmeticException("Expiration overflow");
            }
            requireRevisionCapacity();

            int additions = 0;
            for (int i = 0; i < count; i++) {
                if (find(entries, namesCopy[i]) < 0) {
                    additions++;
                }
            }
            if (entries.length > MAX_ARRAY_LENGTH - additions) {
                throw new IllegalStateException("Cache capacity exceeded");
            }

            long expiration = observationTime + ttl;
            Entry[] replacement = new Entry[entries.length + additions];
            for (int i = 0; i < entries.length; i++) {
                replacement[i] = entries[i];
            }

            int nextIndex = entries.length;
            for (int i = 0; i < count; i++) {
                int index = find(entries, namesCopy[i]);
                if (index < 0) {
                    index = nextIndex++;
                }
                replacement[index] =
                        new Entry(namesCopy[i], ratesCopy[i], expiration);
            }

            // Commit only after all validation and allocation have succeeded.
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

        Entry fromEntry;
        Entry toEntry;
        long capturedRevision;
        long observationTime;

        synchronized (monitor) {
            observationTime = clock.now();
            capturedRevision = revision;

            int fromIndex = find(entries, from);
            int toIndex = find(entries, to);

            if (fromIndex < 0 || toIndex < 0) {
                throw new IllegalStateException(
                        "Missing rate for conversion");
            }

            fromEntry = entries[fromIndex];
            toEntry = entries[toIndex];

            if (fromEntry.expiration() <= observationTime
                    || toEntry.expiration() <= observationTime) {
                throw new IllegalStateException(
                        "Expired rate for conversion");
            }
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
            requireRevisionCapacity();

            Entry[] replacement = new Entry[entries.length - removed];
            int nextIndex = 0;
            for (int i = 0; i < entries.length; i++) {
                if (entries[i].expiration() > observationTime) {
                    replacement[nextIndex++] = entries[i];
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

    private void requireRevisionCapacity() {
        if (revision == MAX_LONG) {
            throw new ArithmeticException("Revision overflow");
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

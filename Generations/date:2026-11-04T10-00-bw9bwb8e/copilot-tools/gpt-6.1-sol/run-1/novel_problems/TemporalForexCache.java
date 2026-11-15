@TemporalForexCache.ThreadSafe
public final class TemporalForexCache {
    @interface ThreadSafe {}

    public interface Clock {
        long now();
    }

    public record Conversion(double value, long revision, long observationTime) {}

    public record Entry(String currency, double rate, long expiration) {}

    public record Snapshot(Entry[] entries, long revision, long observationTime) {
        public Snapshot {
            if (entries == null) {
                throw new IllegalArgumentException("Entries must not be null");
            }
            entries = copyEntries(entries);
        }

        @Override
        public Entry[] entries() {
            return copyEntries(entries);
        }
    }

    private static final long MAX_REVISION = 0x7fffffffffffffffL;
    private static final double MAX_FINITE_DOUBLE = 0x1.fffffffffffffp1023;

    private final Object monitor = new Object();
    private final Clock clock;

    // Access to both fields is protected by monitor.
    private Entry[] entries = new Entry[0];
    private long revision;

    public TemporalForexCache(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("Clock must not be null");
        }
        this.clock = clock;
    }

    public void updateRate(String currency, double rate, long ttl) {
        updateRatesBatch(new String[] {currency}, new double[] {rate}, ttl);
    }

    public void updateRatesBatch(String[] currencies, double[] rates, long ttl) {
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
            validateRate(ratesCopy[i]);
            for (int j = 0; j < i; j++) {
                if (namesCopy[i].equals(namesCopy[j])) {
                    throw new IllegalArgumentException("Duplicate batch currency");
                }
            }
        }

        synchronized (monitor) {
            long observationTime = clock.now();
            if (observationTime > MAX_REVISION - ttl) {
                throw new IllegalArgumentException("Expiration overflow");
            }
            requireRevisionCapacity();

            long requiredSize = entries.length;
            for (int i = 0; i < count; i++) {
                if (indexOf(entries, namesCopy[i]) < 0) {
                    requiredSize++;
                }
            }
            if (requiredSize > 0x7fffffffL) {
                throw new IllegalStateException("Cache capacity overflow");
            }

            long expiration = observationTime + ttl;
            Entry[] replacement = new Entry[(int) requiredSize];
            for (int i = 0; i < entries.length; i++) {
                replacement[i] = entries[i];
            }

            int next = entries.length;
            for (int i = 0; i < count; i++) {
                int index = indexOf(entries, namesCopy[i]);
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
                    "Amount must be finite and nonnegative");
        }

        Entry fromEntry;
        Entry toEntry;
        long capturedRevision;
        long observationTime;

        synchronized (monitor) {
            observationTime = clock.now();
            capturedRevision = revision;

            int fromIndex = indexOf(entries, from);
            int toIndex = indexOf(entries, to);
            if (fromIndex < 0 || toIndex < 0) {
                throw new IllegalStateException("Missing currency rate");
            }

            fromEntry = entries[fromIndex];
            toEntry = entries[toIndex];
            if (fromEntry.expiration() <= observationTime
                    || toEntry.expiration() <= observationTime) {
                throw new IllegalStateException("Expired currency rate");
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

    private void requireRevisionCapacity() {
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

    private static void validateRate(double rate) {
        if (!(rate > 0.0 && rate <= MAX_FINITE_DOUBLE)) {
            throw new IllegalArgumentException("Rate must be finite and positive");
        }
    }

    private static int indexOf(Entry[] source, String currency) {
        for (int i = 0; i < source.length; i++) {
            if (source[i].currency().equals(currency)) {
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

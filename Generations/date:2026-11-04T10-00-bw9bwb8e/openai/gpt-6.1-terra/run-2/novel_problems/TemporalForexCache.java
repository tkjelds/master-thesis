@TemporalForexCache.ThreadSafe
public final class TemporalForexCache {
    @interface ThreadSafe {}

    public interface Clock {
        long now();
    }

    public static final class Entry {
        public final String currency;
        public final double rate;
        public final long expirationTime;

        private Entry(String currency, double rate, long expirationTime) {
            this.currency = currency;
            this.rate = rate;
            this.expirationTime = expirationTime;
        }
    }

    public static final class Conversion {
        public final double value;
        public final long revision;
        public final long observationTime;

        private Conversion(double value, long revision, long observationTime) {
            this.value = value;
            this.revision = revision;
            this.observationTime = observationTime;
        }
    }

    public static final class Snapshot {
        public final long revision;
        public final long observationTime;
        private final Entry[] entries;

        private Snapshot(Entry[] entries, long revision, long observationTime) {
            this.entries = entries;
            this.revision = revision;
            this.observationTime = observationTime;
        }

        public Entry[] entries() {
            Entry[] copy = new Entry[entries.length];
            for (int i = 0; i < entries.length; i++) {
                copy[i] = entries[i];
            }
            return copy;
        }
    }

    private final Clock clock;
    private final Object monitor = new Object();
    private Entry[] entries = new Entry[0];
    private long revision;

    public TemporalForexCache(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.clock = clock;
    }

    public void updateRate(String currency, double rate, long ttl) {
        updateRatesBatch(new String[] { currency }, new double[] { rate }, ttl);
    }

    public void updateRatesBatch(String[] currencies, double[] rates, long ttl) {
        if (currencies == null || rates == null
                || currencies.length == 0 || currencies.length != rates.length) {
            throw new IllegalArgumentException("invalid batch arrays");
        }
        if (ttl <= 0) {
            throw new IllegalArgumentException("ttl must be positive");
        }

        String[] namesCopy = new String[currencies.length];
        double[] ratesCopy = new double[rates.length];
        for (int i = 0; i < namesCopy.length; i++) {
            namesCopy[i] = currencies[i];
            ratesCopy[i] = rates[i];
        }

        for (int i = 0; i < namesCopy.length; i++) {
            validateCurrency(namesCopy[i]);
            if (!(ratesCopy[i] > 0.0) || ratesCopy[i] > Double.MAX_VALUE) {
                throw new IllegalArgumentException("rate must be positive and finite");
            }
            for (int j = 0; j < i; j++) {
                if (namesCopy[i].equals(namesCopy[j])) {
                    throw new IllegalArgumentException("duplicate currency");
                }
            }
        }

        // Operations wait only for the monitor; there are no capacity waits.
        synchronized (monitor) {
            long observationTime = clock.now();
            if (observationTime > Long.MAX_VALUE - ttl) {
                throw new IllegalStateException("expiration overflow");
            }
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision overflow");
            }
            long expirationTime = observationTime + ttl;

            int newCount = entries.length;
            for (int i = 0; i < namesCopy.length; i++) {
                if (find(entries, namesCopy[i]) < 0) {
                    if (newCount == Integer.MAX_VALUE) {
                        throw new IllegalStateException("too many entries");
                    }
                    newCount++;
                }
            }

            Entry[] replacement = new Entry[newCount];
            for (int i = 0; i < entries.length; i++) {
                replacement[i] = entries[i];
            }

            int next = entries.length;
            for (int i = 0; i < namesCopy.length; i++) {
                int index = find(entries, namesCopy[i]);
                if (index < 0) {
                    index = next++;
                }
                replacement[index] = new Entry(namesCopy[i], ratesCopy[i], expirationTime);
            }

            entries = replacement;
            revision++;
        }
    }

    public Conversion convert(String from, String to, double amount) {
        validateCurrency(from);
        validateCurrency(to);
        if (!(amount >= 0.0) || amount > Double.MAX_VALUE) {
            throw new IllegalArgumentException("amount must be nonnegative and finite");
        }

        Entry fromEntry;
        Entry toEntry;
        long capturedRevision;
        long observationTime;
        synchronized (monitor) {
            observationTime = clock.now();
            int fromIndex = find(entries, from);
            int toIndex = find(entries, to);
            if (fromIndex < 0 || toIndex < 0) {
                throw new IllegalStateException("missing rate");
            }
            fromEntry = entries[fromIndex];
            toEntry = entries[toIndex];
            if (fromEntry.expirationTime <= observationTime
                    || toEntry.expirationTime <= observationTime) {
                throw new IllegalStateException("expired rate");
            }
            capturedRevision = revision;
        }

        double value = amount / fromEntry.rate * toEntry.rate;
        return new Conversion(value, capturedRevision, observationTime);
    }

    public int prune() {
        synchronized (monitor) {
            long observationTime = clock.now();
            int removed = 0;
            for (int i = 0; i < entries.length; i++) {
                if (entries[i].expirationTime <= observationTime) {
                    removed++;
                }
            }
            if (removed == 0) {
                return 0;
            }
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision overflow");
            }

            Entry[] replacement = new Entry[entries.length - removed];
            int next = 0;
            for (int i = 0; i < entries.length; i++) {
                if (entries[i].expirationTime > observationTime) {
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
            Entry[] copy = new Entry[entries.length];
            for (int i = 0; i < entries.length; i++) {
                Entry entry = entries[i];
                copy[i] = new Entry(entry.currency, entry.rate, entry.expirationTime);
            }
            return new Snapshot(copy, revision, observationTime);
        }
    }

    private static void validateCurrency(String currency) {
        if (currency == null || currency.length() == 0) {
            throw new IllegalArgumentException("currency must not be null or empty");
        }
    }

    private static int find(Entry[] entries, String currency) {
        for (int i = 0; i < entries.length; i++) {
            if (entries[i].currency.equals(currency)) {
                return i;
            }
        }
        return -1;
    }
}

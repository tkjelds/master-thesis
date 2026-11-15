@TemporalForexCache.ThreadSafe
public final class TemporalForexCache {
    @interface ThreadSafe {}

    public interface Clock {
        long now();
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

    public static final class Entry {
        public final String currency;
        public final double rate;
        public final long expiration;

        private Entry(String currency, double rate, long expiration) {
            this.currency = currency;
            this.rate = rate;
            this.expiration = expiration;
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
            return entries.clone();
        }
    }

    private final Object monitor = new Object();
    private final Clock clock;
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
        if (currencies == null || rates == null) {
            throw new IllegalArgumentException("batch arrays must not be null");
        }

        String[] namesCopy = currencies.clone();
        double[] ratesCopy = rates.clone();

        if (namesCopy.length == 0 || namesCopy.length != ratesCopy.length) {
            throw new IllegalArgumentException("batch arrays must be nonempty and have equal lengths");
        }
        if (ttl <= 0) {
            throw new IllegalArgumentException("ttl must be positive");
        }

        for (int i = 0; i < namesCopy.length; i++) {
            validateCurrency(namesCopy[i]);
            if (Double.isNaN(ratesCopy[i]) || Double.isInfinite(ratesCopy[i])
                    || ratesCopy[i] <= 0.0) {
                throw new IllegalArgumentException("rates must be positive and finite");
            }
            for (int j = 0; j < i; j++) {
                if (namesCopy[i].equals(namesCopy[j])) {
                    throw new IllegalArgumentException("duplicate currency in batch");
                }
            }
        }

        synchronized (monitor) {
            long now = clock.now();
            if (ttl > Long.MAX_VALUE - now) {
                throw new IllegalArgumentException("expiration would overflow");
            }
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision would overflow");
            }

            int additions = 0;
            for (int i = 0; i < namesCopy.length; i++) {
                if (findEntry(entries, namesCopy[i]) < 0) {
                    additions++;
                }
            }
            long newSize = (long) entries.length + additions;
            if (newSize > Integer.MAX_VALUE) {
                throw new IllegalStateException("too many currencies");
            }

            Entry[] replacement = new Entry[(int) newSize];
            for (int i = 0; i < entries.length; i++) {
                replacement[i] = entries[i];
            }

            long expiration = now + ttl;
            int next = entries.length;
            for (int i = 0; i < namesCopy.length; i++) {
                int index = findEntry(entries, namesCopy[i]);
                if (index < 0) {
                    index = next++;
                }
                replacement[index] = new Entry(namesCopy[i], ratesCopy[i], expiration);
            }

            entries = replacement;
            revision++;
        }
    }

    public Conversion convert(String from, String to, double amount) {
        validateCurrency(from);
        validateCurrency(to);
        if (Double.isNaN(amount) || Double.isInfinite(amount) || amount < 0.0) {
            throw new IllegalArgumentException("amount must be nonnegative and finite");
        }

        double fromRate;
        double toRate;
        long observedRevision;
        long observationTime;

        synchronized (monitor) {
            observationTime = clock.now();
            int fromIndex = findEntry(entries, from);
            int toIndex = findEntry(entries, to);
            if (fromIndex < 0 || toIndex < 0
                    || entries[fromIndex].expiration <= observationTime
                    || entries[toIndex].expiration <= observationTime) {
                throw new IllegalStateException("currency rate is missing or expired");
            }

            fromRate = entries[fromIndex].rate;
            toRate = entries[toIndex].rate;
            observedRevision = revision;
        }

        double value = amount / fromRate * toRate;
        return new Conversion(value, observedRevision, observationTime);
    }

    public int prune() {
        synchronized (monitor) {
            long now = clock.now();
            int removed = 0;
            for (int i = 0; i < entries.length; i++) {
                if (entries[i].expiration <= now) {
                    removed++;
                }
            }

            if (removed == 0) {
                return 0;
            }
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision would overflow");
            }

            Entry[] retained = new Entry[entries.length - removed];
            int next = 0;
            for (int i = 0; i < entries.length; i++) {
                if (entries[i].expiration > now) {
                    retained[next++] = entries[i];
                }
            }

            entries = retained;
            revision++;
            return removed;
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            long now = clock.now();
            Entry[] copy = new Entry[entries.length];
            for (int i = 0; i < entries.length; i++) {
                Entry entry = entries[i];
                copy[i] = new Entry(entry.currency, entry.rate, entry.expiration);
            }
            return new Snapshot(copy, revision, now);
        }
    }

    private static void validateCurrency(String currency) {
        if (currency == null || currency.length() == 0) {
            throw new IllegalArgumentException("currency must not be null or empty");
        }
    }

    private static int findEntry(Entry[] entries, String currency) {
        for (int i = 0; i < entries.length; i++) {
            if (entries[i].currency.equals(currency)) {
                return i;
            }
        }
        return -1;
    }
}

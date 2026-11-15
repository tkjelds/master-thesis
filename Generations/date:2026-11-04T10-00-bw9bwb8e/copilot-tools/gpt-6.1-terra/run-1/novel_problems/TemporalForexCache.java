@TemporalForexCache.ThreadSafe
public final class TemporalForexCache {
    @interface ThreadSafe {}

    public interface Clock {
        long now();
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

        public Entry[] getEntries() {
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
        if (currencies == null || rates == null ||
                currencies.length == 0 || currencies.length != rates.length) {
            throw new IllegalArgumentException("batch arrays must be nonempty and have equal lengths");
        }
        if (ttl <= 0) {
            throw new IllegalArgumentException("ttl must be positive");
        }

        String[] currencyCopy = new String[currencies.length];
        double[] rateCopy = new double[rates.length];
        for (int i = 0; i < currencies.length; i++) {
            currencyCopy[i] = currencies[i];
            rateCopy[i] = rates[i];
        }

        for (int i = 0; i < currencyCopy.length; i++) {
            validateCurrency(currencyCopy[i]);
            if (!Double.isFinite(rateCopy[i]) || rateCopy[i] <= 0.0) {
                throw new IllegalArgumentException("rates must be positive and finite");
            }
            for (int j = 0; j < i; j++) {
                if (currencyCopy[i].equals(currencyCopy[j])) {
                    throw new IllegalArgumentException("duplicate currency in batch");
                }
            }
        }

        synchronized (monitor) {
            long observationTime = clock.now();
            if (observationTime > Long.MAX_VALUE - ttl) {
                throw new IllegalArgumentException("expiration would overflow");
            }
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision would overflow");
            }
            long expiration = observationTime + ttl;

            int additions = 0;
            for (int i = 0; i < currencyCopy.length; i++) {
                if (find(entries, currencyCopy[i]) < 0) {
                    additions++;
                }
            }
            if (additions > Integer.MAX_VALUE - entries.length) {
                throw new IllegalStateException("too many currencies");
            }

            Entry[] updated = new Entry[entries.length + additions];
            for (int i = 0; i < entries.length; i++) {
                updated[i] = entries[i];
            }
            int next = entries.length;
            for (int i = 0; i < currencyCopy.length; i++) {
                int index = find(entries, currencyCopy[i]);
                if (index < 0) {
                    index = next++;
                }
                updated[index] = new Entry(currencyCopy[i], rateCopy[i], expiration);
            }

            entries = updated;
            revision++;
        }
    }

    public Conversion convert(String from, String to, double amount) {
        validateCurrency(from);
        validateCurrency(to);
        if (!Double.isFinite(amount) || amount < 0.0) {
            throw new IllegalArgumentException("amount must be nonnegative and finite");
        }

        double fromRate;
        double toRate;
        long capturedRevision;
        long observationTime;
        synchronized (monitor) {
            observationTime = clock.now();
            int fromIndex = find(entries, from);
            int toIndex = find(entries, to);
            if (fromIndex < 0 || toIndex < 0 ||
                    entries[fromIndex].expiration <= observationTime ||
                    entries[toIndex].expiration <= observationTime) {
                throw new IllegalStateException("missing or expired rate");
            }
            fromRate = entries[fromIndex].rate;
            toRate = entries[toIndex].rate;
            capturedRevision = revision;
        }

        return new Conversion(amount / fromRate * toRate, capturedRevision, observationTime);
    }

    public int prune() {
        synchronized (monitor) {
            long observationTime = clock.now();
            int removed = 0;
            for (int i = 0; i < entries.length; i++) {
                if (entries[i].expiration <= observationTime) {
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
                if (entries[i].expiration > observationTime) {
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
            long observationTime = clock.now();
            Entry[] copy = new Entry[entries.length];
            for (int i = 0; i < entries.length; i++) {
                copy[i] = entries[i];
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

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

        public double value() {
            return value;
        }

        public long revision() {
            return revision;
        }

        public long observationTime() {
            return observationTime;
        }
    }

    public static final class RateEntry {
        public final String currency;
        public final double rate;
        public final long expiration;

        private RateEntry(String currency, double rate, long expiration) {
            this.currency = currency;
            this.rate = rate;
            this.expiration = expiration;
        }

        public String currency() {
            return currency;
        }

        public double rate() {
            return rate;
        }

        public long expiration() {
            return expiration;
        }
    }

    public static final class Snapshot {
        public final long revision;
        public final long observationTime;
        private final RateEntry[] entries;

        private Snapshot(RateEntry[] entries, long revision, long observationTime) {
            this.entries = entries;
            this.revision = revision;
            this.observationTime = observationTime;
        }

        public long revision() {
            return revision;
        }

        public long observationTime() {
            return observationTime;
        }

        public int size() {
            return entries.length;
        }

        public RateEntry entryAt(int index) {
            return entries[index];
        }

        public RateEntry[] entries() {
            return entries.clone();
        }
    }

    private final Clock clock;
    private RateEntry[] entries;
    private int size;
    private long revision;

    public TemporalForexCache(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.clock = clock;
        this.entries = new RateEntry[0];
        this.size = 0;
        this.revision = 0L;
    }

    public void updateRate(String currency, double rate, long ttl) {
        updateRatesBatch(new String[] { currency }, new double[] { rate }, ttl);
    }

    public void updateRatesBatch(String[] currencies, double[] rates, long ttl) {
        if (currencies == null || rates == null) {
            throw new IllegalArgumentException("batch arrays must not be null");
        }

        String[] copiedCurrencies = currencies.clone();
        double[] copiedRates = rates.clone();

        if (copiedCurrencies.length == 0
                || copiedCurrencies.length != copiedRates.length) {
            throw new IllegalArgumentException("batch arrays must be nonempty and have equal lengths");
        }
        if (ttl <= 0L) {
            throw new IllegalArgumentException("ttl must be positive");
        }

        for (int i = 0; i < copiedCurrencies.length; i++) {
            validateCurrency(copiedCurrencies[i]);
            validateRate(copiedRates[i]);
            for (int j = 0; j < i; j++) {
                if (copiedCurrencies[i].equals(copiedCurrencies[j])) {
                    throw new IllegalArgumentException("duplicate currency in batch");
                }
            }
        }

        synchronized (this) {
            long observedAt = clock.now();
            if (ttl > Long.MAX_VALUE - observedAt) {
                throw new IllegalStateException("expiration overflow");
            }
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision overflow");
            }

            long expiration = observedAt + ttl;
            int[] indexes = new int[copiedCurrencies.length];
            int additions = 0;

            for (int i = 0; i < copiedCurrencies.length; i++) {
                int index = findIndex(copiedCurrencies[i]);
                if (index < 0) {
                    index = size + additions;
                    additions++;
                }
                indexes[i] = index;
            }

            if (additions > Integer.MAX_VALUE - size) {
                throw new IllegalStateException("cache is too large");
            }
            int required = size + additions;

            RateEntry[] replacements = new RateEntry[copiedCurrencies.length];
            for (int i = 0; i < copiedCurrencies.length; i++) {
                replacements[i] = new RateEntry(
                        copiedCurrencies[i], copiedRates[i], expiration);
            }

            RateEntry[] target = entries;
            if (required > entries.length) {
                target = new RateEntry[required];
                for (int i = 0; i < size; i++) {
                    target[i] = entries[i];
                }
            }

            entries = target;
            for (int i = 0; i < replacements.length; i++) {
                entries[indexes[i]] = replacements[i];
            }
            size = required;
            revision++;
        }
    }

    public Conversion convert(String from, String to, double amount) {
        validateCurrency(from);
        validateCurrency(to);
        if (Double.isNaN(amount) || Double.isInfinite(amount) || amount < 0.0) {
            throw new IllegalArgumentException("amount must be finite and nonnegative");
        }

        RateEntry fromEntry;
        RateEntry toEntry;
        long capturedRevision;
        long observedAt;

        synchronized (this) {
            observedAt = clock.now();
            fromEntry = findEntry(from);
            toEntry = findEntry(to);
            capturedRevision = revision;

            if (fromEntry == null || fromEntry.expiration <= observedAt) {
                throw new IllegalStateException("missing or expired rate: " + from);
            }
            if (toEntry == null || toEntry.expiration <= observedAt) {
                throw new IllegalStateException("missing or expired rate: " + to);
            }
        }

        double converted = amount / fromEntry.rate * toEntry.rate;
        return new Conversion(converted, capturedRevision, observedAt);
    }

    public int prune() {
        synchronized (this) {
            long observedAt = clock.now();
            int removed = 0;
            for (int i = 0; i < size; i++) {
                if (entries[i].expiration <= observedAt) {
                    removed++;
                }
            }

            if (removed == 0) {
                return 0;
            }
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision overflow");
            }

            RateEntry[] retained = new RateEntry[size - removed];
            int next = 0;
            for (int i = 0; i < size; i++) {
                RateEntry entry = entries[i];
                if (entry.expiration > observedAt) {
                    retained[next++] = entry;
                }
            }

            entries = retained;
            size = retained.length;
            revision++;
            return removed;
        }
    }

    public Snapshot snapshot() {
        synchronized (this) {
            long observedAt = clock.now();
            RateEntry[] copiedEntries = new RateEntry[size];
            for (int i = 0; i < size; i++) {
                RateEntry entry = entries[i];
                copiedEntries[i] = new RateEntry(
                        entry.currency, entry.rate, entry.expiration);
            }
            return new Snapshot(copiedEntries, revision, observedAt);
        }
    }

    private int findIndex(String currency) {
        for (int i = 0; i < size; i++) {
            if (entries[i].currency.equals(currency)) {
                return i;
            }
        }
        return -1;
    }

    private RateEntry findEntry(String currency) {
        int index = findIndex(currency);
        return index < 0 ? null : entries[index];
    }

    private static void validateCurrency(String currency) {
        if (currency == null || currency.length() == 0) {
            throw new IllegalArgumentException("currency must not be null or empty");
        }
    }

    private static void validateRate(double rate) {
        if (Double.isNaN(rate) || Double.isInfinite(rate) || rate <= 0.0) {
            throw new IllegalArgumentException("rate must be positive and finite");
        }
    }
}

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

        public Conversion(double value, long revision, long observationTime) {
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

    public static final class Entry {
        public final String currency;
        public final double rate;
        public final long expirationTime;

        public Entry(String currency, double rate, long expirationTime) {
            this.currency = currency;
            this.rate = rate;
            this.expirationTime = expirationTime;
        }

        public String currency() {
            return currency;
        }

        public double rate() {
            return rate;
        }

        public long expirationTime() {
            return expirationTime;
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

        public long revision() {
            return revision;
        }

        public long observationTime() {
            return observationTime;
        }

        public int size() {
            return entries.length;
        }

        public Entry entryAt(int index) {
            return entries[index];
        }

        public Entry[] entries() {
            Entry[] copy = new Entry[entries.length];
            for (int i = 0; i < entries.length; i++) {
                copy[i] = entries[i];
            }
            return copy;
        }
    }

    private static final class CacheState {
        final String[] currencies;
        final double[] rates;
        final long[] expirations;
        final long revision;

        CacheState(String[] currencies, double[] rates, long[] expirations, long revision) {
            this.currencies = currencies;
            this.rates = rates;
            this.expirations = expirations;
            this.revision = revision;
        }

        int find(String currency) {
            for (int i = 0; i < currencies.length; i++) {
                if (currencies[i].equals(currency)) {
                    return i;
                }
            }
            return -1;
        }
    }

    private final Object lock = new Object();
    private final Clock clock;
    private CacheState state = new CacheState(
            new String[0], new double[0], new long[0], 0L);

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
        if (currencies.length == 0 || currencies.length != rates.length) {
            throw new IllegalArgumentException("batch arrays must be nonempty and have equal lengths");
        }

        String[] copiedCurrencies = new String[currencies.length];
        double[] copiedRates = new double[rates.length];
        for (int i = 0; i < currencies.length; i++) {
            copiedCurrencies[i] = currencies[i];
            copiedRates[i] = rates[i];
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

        synchronized (lock) {
            long observationTime = clock.now();
            if (ttl > Long.MAX_VALUE - observationTime) {
                throw new IllegalArgumentException("expiration time overflow");
            }
            if (state.revision == Long.MAX_VALUE) {
                throw new IllegalStateException("cache revision overflow");
            }

            long expirationTime = observationTime + ttl;
            CacheState oldState = state;

            int newCurrencyCount = 0;
            for (int i = 0; i < copiedCurrencies.length; i++) {
                if (oldState.find(copiedCurrencies[i]) < 0) {
                    newCurrencyCount++;
                }
            }
            if (newCurrencyCount > Integer.MAX_VALUE - oldState.currencies.length) {
                throw new IllegalStateException("cache is too large");
            }

            int resultLength = oldState.currencies.length + newCurrencyCount;
            String[] nextCurrencies = new String[resultLength];
            double[] nextRates = new double[resultLength];
            long[] nextExpirations = new long[resultLength];

            for (int i = 0; i < oldState.currencies.length; i++) {
                nextCurrencies[i] = oldState.currencies[i];
                nextRates[i] = oldState.rates[i];
                nextExpirations[i] = oldState.expirations[i];
            }

            int nextLength = oldState.currencies.length;
            for (int i = 0; i < copiedCurrencies.length; i++) {
                int index = find(nextCurrencies, nextLength, copiedCurrencies[i]);
                if (index < 0) {
                    index = nextLength++;
                    nextCurrencies[index] = copiedCurrencies[i];
                }
                nextRates[index] = copiedRates[i];
                nextExpirations[index] = expirationTime;
            }

            state = new CacheState(
                    nextCurrencies, nextRates, nextExpirations, oldState.revision + 1L);
        }
    }

    public Conversion convert(String from, String to, double amount) {
        validateCurrency(from);
        validateCurrency(to);
        if (amount != amount || amount < 0.0 || amount > Double.MAX_VALUE) {
            throw new IllegalArgumentException("amount must be finite and nonnegative");
        }

        final double fromRate;
        final double toRate;
        final long capturedRevision;
        final long observationTime;

        synchronized (lock) {
            observationTime = clock.now();
            CacheState capturedState = state;

            int fromIndex = capturedState.find(from);
            int toIndex = capturedState.find(to);
            if (fromIndex < 0 || toIndex < 0) {
                throw new IllegalStateException("missing currency rate");
            }

            long fromExpiration = capturedState.expirations[fromIndex];
            long toExpiration = capturedState.expirations[toIndex];
            fromRate = capturedState.rates[fromIndex];
            toRate = capturedState.rates[toIndex];
            capturedRevision = capturedState.revision;

            if (fromExpiration <= observationTime || toExpiration <= observationTime) {
                throw new IllegalStateException("expired currency rate");
            }
        }

        double value = amount / fromRate * toRate;
        return new Conversion(value, capturedRevision, observationTime);
    }

    public int prune() {
        synchronized (lock) {
            long observationTime = clock.now();
            CacheState oldState = state;

            int removed = 0;
            for (int i = 0; i < oldState.currencies.length; i++) {
                if (oldState.expirations[i] <= observationTime) {
                    removed++;
                }
            }
            if (removed == 0) {
                return 0;
            }
            if (oldState.revision == Long.MAX_VALUE) {
                throw new IllegalStateException("cache revision overflow");
            }

            int remaining = oldState.currencies.length - removed;
            String[] nextCurrencies = new String[remaining];
            double[] nextRates = new double[remaining];
            long[] nextExpirations = new long[remaining];

            int nextIndex = 0;
            for (int i = 0; i < oldState.currencies.length; i++) {
                if (oldState.expirations[i] > observationTime) {
                    nextCurrencies[nextIndex] = oldState.currencies[i];
                    nextRates[nextIndex] = oldState.rates[i];
                    nextExpirations[nextIndex] = oldState.expirations[i];
                    nextIndex++;
                }
            }

            state = new CacheState(
                    nextCurrencies, nextRates, nextExpirations, oldState.revision + 1L);
            return removed;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            long observationTime = clock.now();
            CacheState capturedState = state;
            Entry[] entries = new Entry[capturedState.currencies.length];

            for (int i = 0; i < capturedState.currencies.length; i++) {
                entries[i] = new Entry(
                        capturedState.currencies[i],
                        capturedState.rates[i],
                        capturedState.expirations[i]);
            }

            return new Snapshot(entries, capturedState.revision, observationTime);
        }
    }

    private static void validateCurrency(String currency) {
        if (currency == null || currency.length() == 0) {
            throw new IllegalArgumentException("currency must not be null or empty");
        }
    }

    private static void validateRate(double rate) {
        if (!(rate > 0.0) || rate > Double.MAX_VALUE) {
            throw new IllegalArgumentException("rate must be positive and finite");
        }
    }

    private static int find(String[] currencies, int length, String currency) {
        for (int i = 0; i < length; i++) {
            if (currencies[i].equals(currency)) {
                return i;
            }
        }
        return -1;
    }
}

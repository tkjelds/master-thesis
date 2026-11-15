@TemporalForexCache.ThreadSafe
public final class TemporalForexCache {
    @interface ThreadSafe {}

    public interface Clock {
        long now();
    }

    public record Conversion(double value, long revision, long observationTime) {}

    public record RateEntry(String currency, double rate, long expirationTime) {}

    public static final class Snapshot {
        private final RateEntry[] entries;
        private final long revision;
        private final long observationTime;

        private Snapshot(RateEntry[] entries, long revision, long observationTime) {
            this.entries = entries;
            this.revision = revision;
            this.observationTime = observationTime;
        }

        public RateEntry[] entries() {
            return entries.clone();
        }

        public long revision() {
            return revision;
        }

        public long observationTime() {
            return observationTime;
        }
    }

    private final Clock clock;
    private final Object monitor = new Object();

    private String[] currencies = new String[0];
    private double[] rates = new double[0];
    private long[] expirations = new long[0];
    private int size;
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
        if (currencies == null || rates == null || currencies.length == 0
                || currencies.length != rates.length) {
            throw new IllegalArgumentException("batch arrays must be non-null, non-empty, and equal in length");
        }

        String[] copiedCurrencies = new String[currencies.length];
        double[] copiedRates = new double[rates.length];
        for (int i = 0; i < currencies.length; i++) {
            copiedCurrencies[i] = currencies[i];
            copiedRates[i] = rates[i];
        }

        if (ttl <= 0) {
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

        synchronized (monitor) {
            long observationTime = clock.now();
            if (ttl > Long.MAX_VALUE - observationTime) {
                throw new IllegalArgumentException("expiration time overflow");
            }
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("cache revision overflow");
            }

            int additions = 0;
            for (String currency : copiedCurrencies) {
                if (findIndex(this.currencies, size, currency) < 0) {
                    additions++;
                }
            }
            if (additions > Integer.MAX_VALUE - size) {
                throw new IllegalStateException("cache size overflow");
            }

            int capacity = size + additions;
            String[] nextCurrencies = new String[capacity];
            double[] nextRates = new double[capacity];
            long[] nextExpirations = new long[capacity];

            for (int i = 0; i < size; i++) {
                nextCurrencies[i] = this.currencies[i];
                nextRates[i] = this.rates[i];
                nextExpirations[i] = this.expirations[i];
            }

            int nextSize = size;
            long expirationTime = observationTime + ttl;
            for (int i = 0; i < copiedCurrencies.length; i++) {
                int index = findIndex(nextCurrencies, nextSize, copiedCurrencies[i]);
                if (index < 0) {
                    index = nextSize++;
                    nextCurrencies[index] = copiedCurrencies[i];
                }
                nextRates[index] = copiedRates[i];
                nextExpirations[index] = expirationTime;
            }

            this.currencies = nextCurrencies;
            this.rates = nextRates;
            this.expirations = nextExpirations;
            this.size = nextSize;
            this.revision++;
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
        long capturedRevision;
        long observationTime;

        synchronized (monitor) {
            observationTime = clock.now();
            int fromIndex = findIndex(currencies, size, from);
            int toIndex = findIndex(currencies, size, to);
            if (fromIndex < 0 || toIndex < 0
                    || expirations[fromIndex] <= observationTime
                    || expirations[toIndex] <= observationTime) {
                throw new IllegalStateException("missing or expired currency rate");
            }

            fromRate = rates[fromIndex];
            toRate = rates[toIndex];
            capturedRevision = revision;
        }

        return new Conversion(amount / fromRate * toRate, capturedRevision, observationTime);
    }

    public int prune() {
        synchronized (monitor) {
            long observationTime = clock.now();
            int removed = 0;
            for (int i = 0; i < size; i++) {
                if (expirations[i] <= observationTime) {
                    removed++;
                }
            }
            if (removed == 0) {
                return 0;
            }
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("cache revision overflow");
            }

            int retained = size - removed;
            String[] nextCurrencies = new String[retained];
            double[] nextRates = new double[retained];
            long[] nextExpirations = new long[retained];

            int nextIndex = 0;
            for (int i = 0; i < size; i++) {
                if (expirations[i] > observationTime) {
                    nextCurrencies[nextIndex] = currencies[i];
                    nextRates[nextIndex] = rates[i];
                    nextExpirations[nextIndex] = expirations[i];
                    nextIndex++;
                }
            }

            currencies = nextCurrencies;
            rates = nextRates;
            expirations = nextExpirations;
            size = retained;
            revision++;
            return removed;
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            long observationTime = clock.now();
            RateEntry[] entries = new RateEntry[size];
            for (int i = 0; i < size; i++) {
                entries[i] = new RateEntry(currencies[i], rates[i], expirations[i]);
            }
            return new Snapshot(entries, revision, observationTime);
        }
    }

    private static int findIndex(String[] currencies, int size, String currency) {
        for (int i = 0; i < size; i++) {
            if (currencies[i].equals(currency)) {
                return i;
            }
        }
        return -1;
    }

    private static void validateCurrency(String currency) {
        if (currency == null || currency.isEmpty()) {
            throw new IllegalArgumentException("currency must not be null or empty");
        }
    }

    private static void validateRate(double rate) {
        if (Double.isNaN(rate) || Double.isInfinite(rate) || rate <= 0.0) {
            throw new IllegalArgumentException("rate must be positive and finite");
        }
    }
}

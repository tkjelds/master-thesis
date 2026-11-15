@TemporalForexCache.ThreadSafe
public final class TemporalForexCache {
    @interface ThreadSafe {}

    public interface Clock {
        long now();
    }

    public static record Conversion(double value, long revision, long observationTime) {
    }

    public static record RateEntry(String currency, double rate, long expiration) {
    }

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

    private final Object monitor = new Object();
    private final Clock clock;
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
        if (ttl <= 0) {
            throw new IllegalArgumentException("ttl must be positive");
        }

        String[] copiedCurrencies = currencies.clone();
        double[] copiedRates = rates.clone();
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
            if (observationTime > Long.MAX_VALUE - ttl) {
                throw new IllegalStateException("expiration overflow");
            }
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("cache revision overflow");
            }

            long expiration = observationTime + ttl;
            int additions = 0;
            for (int i = 0; i < copiedCurrencies.length; i++) {
                if (findCurrency(copiedCurrencies[i]) < 0) {
                    additions++;
                }
            }
            if (size > Integer.MAX_VALUE - additions) {
                throw new IllegalStateException("cache is too large");
            }
            ensureCapacity(size + additions);

            for (int i = 0; i < copiedCurrencies.length; i++) {
                int index = findCurrency(copiedCurrencies[i]);
                if (index < 0) {
                    index = size++;
                    this.currencies[index] = copiedCurrencies[i];
                }
                this.rates[index] = copiedRates[i];
                expirations[index] = expiration;
            }
            revision++;
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
        long fromExpiration;
        long toExpiration;
        long capturedRevision;
        long observationTime;
        synchronized (monitor) {
            observationTime = clock.now();
            int fromIndex = findCurrency(from);
            int toIndex = findCurrency(to);
            if (fromIndex < 0 || toIndex < 0) {
                throw new IllegalStateException("a required currency rate is missing or expired");
            }
            fromRate = rates[fromIndex];
            toRate = rates[toIndex];
            fromExpiration = expirations[fromIndex];
            toExpiration = expirations[toIndex];
            capturedRevision = revision;
            if (fromExpiration <= observationTime || toExpiration <= observationTime) {
                throw new IllegalStateException("a required currency rate is missing or expired");
            }
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

            int destination = 0;
            for (int source = 0; source < size; source++) {
                if (expirations[source] > observationTime) {
                    if (destination != source) {
                        currencies[destination] = currencies[source];
                        rates[destination] = rates[source];
                        expirations[destination] = expirations[source];
                    }
                    destination++;
                }
            }
            for (int i = destination; i < size; i++) {
                currencies[i] = null;
                rates[i] = 0.0;
                expirations[i] = 0L;
            }
            size = destination;
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

    private int findCurrency(String currency) {
        for (int i = 0; i < size; i++) {
            if (currencies[i].equals(currency)) {
                return i;
            }
        }
        return -1;
    }

    private void ensureCapacity(int required) {
        if (required <= currencies.length) {
            return;
        }

        int capacity = currencies.length == 0 ? 1 : currencies.length;
        while (capacity < required) {
            if (capacity > Integer.MAX_VALUE / 2) {
                capacity = required;
                break;
            }
            capacity *= 2;
        }

        String[] newCurrencies = new String[capacity];
        double[] newRates = new double[capacity];
        long[] newExpirations = new long[capacity];
        for (int i = 0; i < size; i++) {
            newCurrencies[i] = currencies[i];
            newRates[i] = rates[i];
            newExpirations[i] = expirations[i];
        }
        currencies = newCurrencies;
        rates = newRates;
        expirations = newExpirations;
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

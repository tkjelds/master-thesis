@TemporalForexCache.ThreadSafe
public final class TemporalForexCache {
    @interface ThreadSafe {}

    public interface Clock {
        long now();
    }

    public record Conversion(double value, long revision, long observationTime) {
    }

    public record RateEntry(String currency, double rate, long expirationTime) {
    }

    public record Snapshot(RateEntry[] entries, long revision, long observationTime) {
        public Snapshot {
            if (entries == null) {
                throw new IllegalArgumentException("entries must not be null");
            }
            entries = entries.clone();
        }

        @Override
        public RateEntry[] entries() {
            return entries.clone();
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
            throw new IllegalArgumentException("batch arrays must be nonempty and have matching lengths");
        }
        if (ttl <= 0) {
            throw new IllegalArgumentException("ttl must be positive");
        }

        String[] batchCurrencies = currencies.clone();
        double[] batchRates = rates.clone();

        for (int i = 0; i < batchCurrencies.length; i++) {
            validateCurrency(batchCurrencies[i]);
            validateRate(batchRates[i]);
            for (int j = 0; j < i; j++) {
                if (batchCurrencies[i].equals(batchCurrencies[j])) {
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
            long expirationTime = observationTime + ttl;

            int additions = 0;
            for (String currency : batchCurrencies) {
                if (findIndex(this.currencies, size, currency) < 0) {
                    additions++;
                }
            }
            if (additions > Integer.MAX_VALUE - size) {
                throw new IllegalStateException("cache capacity exceeded");
            }

            int newSize = size + additions;
            String[] newCurrencies = new String[newSize];
            double[] newRates = new double[newSize];
            long[] newExpirations = new long[newSize];

            System.arraycopy(this.currencies, 0, newCurrencies, 0, size);
            System.arraycopy(this.rates, 0, newRates, 0, size);
            System.arraycopy(this.expirations, 0, newExpirations, 0, size);

            int populatedSize = size;
            for (int i = 0; i < batchCurrencies.length; i++) {
                int index = findIndex(newCurrencies, populatedSize, batchCurrencies[i]);
                if (index < 0) {
                    index = populatedSize++;
                    newCurrencies[index] = batchCurrencies[i];
                }
                newRates[index] = batchRates[i];
                newExpirations[index] = expirationTime;
            }

            this.currencies = newCurrencies;
            this.rates = newRates;
            this.expirations = newExpirations;
            this.size = newSize;
            this.revision++;
        }
    }

    public Conversion convert(String from, String to, double amount) {
        validateCurrency(from);
        validateCurrency(to);
        if (!Double.isFinite(amount) || amount < 0.0) {
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
                throw new IllegalStateException("missing or expired exchange rate");
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

            int writeIndex = 0;
            for (int readIndex = 0; readIndex < size; readIndex++) {
                if (expirations[readIndex] > observationTime) {
                    if (writeIndex != readIndex) {
                        currencies[writeIndex] = currencies[readIndex];
                        rates[writeIndex] = rates[readIndex];
                        expirations[writeIndex] = expirations[readIndex];
                    }
                    writeIndex++;
                }
            }
            size = writeIndex;
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

    private static void validateCurrency(String currency) {
        if (currency == null || currency.isEmpty()) {
            throw new IllegalArgumentException("currency must not be null or empty");
        }
    }

    private static void validateRate(double rate) {
        if (!Double.isFinite(rate) || rate <= 0.0) {
            throw new IllegalArgumentException("rate must be positive and finite");
        }
    }

    private static int findIndex(String[] currencies, int count, String currency) {
        for (int i = 0; i < count; i++) {
            if (currencies[i].equals(currency)) {
                return i;
            }
        }
        return -1;
    }
}

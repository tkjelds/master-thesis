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
            entries = entries.clone();
        }

        @Override
        public Entry[] entries() {
            return entries.clone();
        }
    }

    private final Clock clock;
    private final Object monitor = new Object();

    // These arrays are replaced together while holding monitor.
    private String[] currencies = new String[0];
    private double[] rates = new double[0];
    private long[] expirations = new long[0];
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

        String[] currencyCopy = currencies.clone();
        double[] rateCopy = rates.clone();

        if (currencyCopy.length == 0 || currencyCopy.length != rateCopy.length) {
            throw new IllegalArgumentException("batch arrays must be nonempty and have equal lengths");
        }
        if (ttl <= 0) {
            throw new IllegalArgumentException("ttl must be positive");
        }

        for (int i = 0; i < currencyCopy.length; i++) {
            validateCurrency(currencyCopy[i]);
            validateRate(rateCopy[i]);
            for (int j = 0; j < i; j++) {
                if (currencyCopy[i].equals(currencyCopy[j])) {
                    throw new IllegalArgumentException("duplicate currency in batch");
                }
            }
        }

        synchronized (monitor) {
            long observationTime = clock.now();
            if (ttl > Long.MAX_VALUE - observationTime) {
                throw new ArithmeticException("expiration overflow");
            }
            if (revision == Long.MAX_VALUE) {
                throw new ArithmeticException("revision overflow");
            }
            long expiration = observationTime + ttl;

            int additions = 0;
            for (int i = 0; i < currencyCopy.length; i++) {
                if (findCurrency(currencies, currencyCopy[i]) < 0) {
                    additions++;
                }
            }

            int newSize = currencies.length + additions;
            String[] newCurrencies = new String[newSize];
            double[] newRates = new double[newSize];
            long[] newExpirations = new long[newSize];

            System.arraycopy(currencies, 0, newCurrencies, 0, currencies.length);
            System.arraycopy(rates, 0, newRates, 0, rates.length);
            System.arraycopy(expirations, 0, newExpirations, 0, expirations.length);

            int next = currencies.length;
            for (int i = 0; i < currencyCopy.length; i++) {
                int index = findCurrency(newCurrencies, currencies.length, currencyCopy[i]);
                if (index < 0) {
                    index = next++;
                    newCurrencies[index] = currencyCopy[i];
                }
                newRates[index] = rateCopy[i];
                newExpirations[index] = expiration;
            }

            this.currencies = newCurrencies;
            this.rates = newRates;
            this.expirations = newExpirations;
            revision++;
        }
    }

    public Conversion convert(String from, String to, double amount) {
        validateCurrency(from);
        validateCurrency(to);
        if (Double.isNaN(amount) || Double.isInfinite(amount) || amount < 0.0) {
            throw new IllegalArgumentException("amount must be finite and nonnegative");
        }

        final double fromRate;
        final double toRate;
        final long capturedRevision;
        final long observationTime;

        synchronized (monitor) {
            observationTime = clock.now();
            int fromIndex = findCurrency(currencies, from);
            int toIndex = findCurrency(currencies, to);

            if (fromIndex < 0 || expirations[fromIndex] <= observationTime
                    || toIndex < 0 || expirations[toIndex] <= observationTime) {
                throw new IllegalStateException("currency rate is missing or expired");
            }

            fromRate = rates[fromIndex];
            toRate = rates[toIndex];
            capturedRevision = revision;
        }

        double value = amount / fromRate * toRate;
        return new Conversion(value, capturedRevision, observationTime);
    }

    public int prune() {
        synchronized (monitor) {
            long observationTime = clock.now();
            int removed = 0;
            for (int i = 0; i < currencies.length; i++) {
                if (expirations[i] <= observationTime) {
                    removed++;
                }
            }
            if (removed == 0) {
                return 0;
            }
            if (revision == Long.MAX_VALUE) {
                throw new ArithmeticException("revision overflow");
            }

            int newSize = currencies.length - removed;
            String[] newCurrencies = new String[newSize];
            double[] newRates = new double[newSize];
            long[] newExpirations = new long[newSize];

            int next = 0;
            for (int i = 0; i < currencies.length; i++) {
                if (expirations[i] > observationTime) {
                    newCurrencies[next] = currencies[i];
                    newRates[next] = rates[i];
                    newExpirations[next] = expirations[i];
                    next++;
                }
            }

            currencies = newCurrencies;
            rates = newRates;
            expirations = newExpirations;
            revision++;
            return removed;
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            long observationTime = clock.now();
            Entry[] entries = new Entry[currencies.length];
            for (int i = 0; i < currencies.length; i++) {
                entries[i] = new Entry(currencies[i], rates[i], expirations[i]);
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
        if (Double.isNaN(rate) || Double.isInfinite(rate) || rate <= 0.0) {
            throw new IllegalArgumentException("rate must be positive and finite");
        }
    }

    private static int findCurrency(String[] names, String currency) {
        return findCurrency(names, names.length, currency);
    }

    private static int findCurrency(String[] names, int length, String currency) {
        for (int i = 0; i < length; i++) {
            if (currency.equals(names[i])) {
                return i;
            }
        }
        return -1;
    }
}

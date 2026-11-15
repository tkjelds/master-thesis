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
            entries = entries.clone();
        }

        @Override
        public RateEntry[] entries() {
            return entries.clone();
        }
    }

    private static final class State {
        final String[] currencies;
        final double[] rates;
        final long[] expirations;
        final int count;
        final long revision;

        State(String[] currencies, double[] rates, long[] expirations, int count, long revision) {
            this.currencies = currencies;
            this.rates = rates;
            this.expirations = expirations;
            this.count = count;
            this.revision = revision;
        }
    }

    private final Object monitor = new Object();
    private final Clock clock;
    private State state = new State(new String[0], new double[0], new long[0], 0, 0L);

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
        if (currencies == null || rates == null || currencies.length == 0 || currencies.length != rates.length) {
            throw new IllegalArgumentException("currency and rate arrays must be nonempty and have equal lengths");
        }
        if (ttl <= 0L) {
            throw new IllegalArgumentException("ttl must be positive");
        }

        String[] copiedCurrencies = new String[currencies.length];
        double[] copiedRates = new double[rates.length];
        for (int i = 0; i < currencies.length; i++) {
            copiedCurrencies[i] = currencies[i];
            copiedRates[i] = rates[i];
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
                throw new ArithmeticException("expiration time overflow");
            }

            State current = state;
            if (current.revision == Long.MAX_VALUE) {
                throw new ArithmeticException("cache revision overflow");
            }

            int additions = 0;
            for (int i = 0; i < copiedCurrencies.length; i++) {
                if (findCurrency(current.currencies, current.count, copiedCurrencies[i]) < 0) {
                    additions++;
                }
            }
            if (additions > Integer.MAX_VALUE - current.count) {
                throw new ArithmeticException("cache size overflow");
            }

            int newCount = current.count + additions;
            String[] nextCurrencies = new String[newCount];
            double[] nextRates = new double[newCount];
            long[] nextExpirations = new long[newCount];

            for (int i = 0; i < current.count; i++) {
                nextCurrencies[i] = current.currencies[i];
                nextRates[i] = current.rates[i];
                nextExpirations[i] = current.expirations[i];
            }

            long expirationTime = observationTime + ttl;
            int nextCount = current.count;
            for (int i = 0; i < copiedCurrencies.length; i++) {
                int index = findCurrency(nextCurrencies, nextCount, copiedCurrencies[i]);
                if (index < 0) {
                    index = nextCount++;
                    nextCurrencies[index] = copiedCurrencies[i];
                }
                nextRates[index] = copiedRates[i];
                nextExpirations[index] = expirationTime;
            }

            state = new State(nextCurrencies, nextRates, nextExpirations, newCount, current.revision + 1L);
        }
    }

    public Conversion convert(String from, String to, double amount) {
        validateCurrency(from);
        validateCurrency(to);
        validateAmount(amount);

        double fromRate;
        double toRate;
        long revision;
        long observationTime;

        synchronized (monitor) {
            observationTime = clock.now();
            State current = state;
            int fromIndex = findCurrency(current.currencies, current.count, from);
            int toIndex = findCurrency(current.currencies, current.count, to);

            if (fromIndex < 0 || toIndex < 0
                    || current.expirations[fromIndex] <= observationTime
                    || current.expirations[toIndex] <= observationTime) {
                throw new IllegalStateException("missing or expired currency rate");
            }

            fromRate = current.rates[fromIndex];
            toRate = current.rates[toIndex];
            revision = current.revision;
        }

        return new Conversion(amount / fromRate * toRate, revision, observationTime);
    }

    public int prune() {
        synchronized (monitor) {
            long observationTime = clock.now();
            State current = state;
            int removed = 0;

            for (int i = 0; i < current.count; i++) {
                if (current.expirations[i] <= observationTime) {
                    removed++;
                }
            }
            if (removed == 0) {
                return 0;
            }
            if (current.revision == Long.MAX_VALUE) {
                throw new ArithmeticException("cache revision overflow");
            }

            int newCount = current.count - removed;
            String[] nextCurrencies = new String[newCount];
            double[] nextRates = new double[newCount];
            long[] nextExpirations = new long[newCount];

            int nextIndex = 0;
            for (int i = 0; i < current.count; i++) {
                if (current.expirations[i] > observationTime) {
                    nextCurrencies[nextIndex] = current.currencies[i];
                    nextRates[nextIndex] = current.rates[i];
                    nextExpirations[nextIndex] = current.expirations[i];
                    nextIndex++;
                }
            }

            state = new State(nextCurrencies, nextRates, nextExpirations, newCount, current.revision + 1L);
            return removed;
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            long observationTime = clock.now();
            State current = state;
            RateEntry[] entries = new RateEntry[current.count];

            for (int i = 0; i < current.count; i++) {
                entries[i] = new RateEntry(
                        current.currencies[i],
                        current.rates[i],
                        current.expirations[i]);
            }

            return new Snapshot(entries, current.revision, observationTime);
        }
    }

    private static int findCurrency(String[] currencies, int count, String currency) {
        for (int i = 0; i < count; i++) {
            if (currencies[i].equals(currency)) {
                return i;
            }
        }
        return -1;
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

    private static void validateAmount(double amount) {
        if (!(amount >= 0.0) || amount > Double.MAX_VALUE) {
            throw new IllegalArgumentException("amount must be nonnegative and finite");
        }
    }
}

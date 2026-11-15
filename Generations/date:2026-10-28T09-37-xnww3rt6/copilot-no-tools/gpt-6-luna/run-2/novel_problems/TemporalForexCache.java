@TemporalForexCache.ThreadSafe
public final class TemporalForexCache {
    @interface ThreadSafe {}

    public interface Clock {
        long now();
    }

    public record Conversion(double value, long revision, long observationTime) {
    }

    public record RateSnapshot(String currency, double rate, long expirationTime) {
    }

    public record Snapshot(RateSnapshot[] entries, long revision, long observationTime) {
        public Snapshot {
            if (entries == null) {
                throw new IllegalArgumentException("entries must not be null");
            }
            entries = entries.clone();
        }

        @Override
        public RateSnapshot[] entries() {
            return entries.clone();
        }
    }

    private static final class State {
        private final RateSnapshot[] entries;
        private final long revision;

        private State(RateSnapshot[] entries, long revision) {
            this.entries = entries;
            this.revision = revision;
        }
    }

    private final Object monitor = new Object();
    private final Clock clock;
    private State state = new State(new RateSnapshot[0], 0L);

    public TemporalForexCache(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.clock = clock;
    }

    public void updateRate(String currency, double rate, long ttl) {
        updateRatesBatch(new String[] {currency}, new double[] {rate}, ttl);
    }

    public void updateRatesBatch(String[] currencies, double[] rates, long ttl) {
        if (currencies == null || rates == null) {
            throw new IllegalArgumentException("batch arrays must not be null");
        }

        String[] copiedCurrencies = currencies.clone();
        double[] copiedRates = rates.clone();

        if (copiedCurrencies.length == 0 || copiedCurrencies.length != copiedRates.length) {
            throw new IllegalArgumentException("batch arrays must be nonempty and have matching lengths");
        }
        if (ttl <= 0L) {
            throw new IllegalArgumentException("ttl must be positive");
        }

        for (int i = 0; i < copiedCurrencies.length; i++) {
            validateCurrency(copiedCurrencies[i]);
            validateRate(copiedRates[i]);
            for (int j = 0; j < i; j++) {
                if (copiedCurrencies[i].equals(copiedCurrencies[j])) {
                    throw new IllegalArgumentException("duplicate currency: " + copiedCurrencies[i]);
                }
            }
        }

        synchronized (monitor) {
            State current = state;
            long observationTime = clock.now();
            if (observationTime < 0L) {
                throw new IllegalStateException("clock returned a negative time");
            }
            if (ttl > Long.MAX_VALUE - observationTime) {
                throw new IllegalArgumentException("expiration time overflow");
            }
            if (current.revision == Long.MAX_VALUE) {
                throw new IllegalStateException("cache revision overflow");
            }

            long expirationTime = observationTime + ttl;
            int retainedCount = 0;
            for (RateSnapshot entry : current.entries) {
                if (!contains(copiedCurrencies, entry.currency())) {
                    retainedCount++;
                }
            }

            RateSnapshot[] updatedEntries = new RateSnapshot[retainedCount + copiedCurrencies.length];
            int index = 0;
            for (RateSnapshot entry : current.entries) {
                if (!contains(copiedCurrencies, entry.currency())) {
                    updatedEntries[index++] = entry;
                }
            }
            for (int i = 0; i < copiedCurrencies.length; i++) {
                updatedEntries[index++] =
                        new RateSnapshot(copiedCurrencies[i], copiedRates[i], expirationTime);
            }

            state = new State(updatedEntries, current.revision + 1L);
        }
    }

    public Conversion convert(String from, String to, double amount) {
        validateCurrency(from);
        validateCurrency(to);
        if (!isFinite(amount) || amount < 0.0) {
            throw new IllegalArgumentException("amount must be finite and nonnegative");
        }

        double fromRate;
        double toRate;
        long fromExpiration;
        long toExpiration;
        long revision;
        long observationTime;

        synchronized (monitor) {
            State current = state;
            observationTime = clock.now();
            RateSnapshot fromEntry = find(current.entries, from);
            RateSnapshot toEntry = find(current.entries, to);

            if (fromEntry == null || toEntry == null) {
                throw new IllegalStateException("missing currency rate");
            }

            fromRate = fromEntry.rate();
            toRate = toEntry.rate();
            fromExpiration = fromEntry.expirationTime();
            toExpiration = toEntry.expirationTime();
            revision = current.revision;

            if (fromExpiration <= observationTime || toExpiration <= observationTime) {
                throw new IllegalStateException("currency rate has expired");
            }
        }

        return new Conversion(amount / fromRate * toRate, revision, observationTime);
    }

    public int prune() {
        synchronized (monitor) {
            State current = state;
            long observationTime = clock.now();
            int retainedCount = 0;

            for (RateSnapshot entry : current.entries) {
                if (entry.expirationTime() > observationTime) {
                    retainedCount++;
                }
            }

            int removedCount = current.entries.length - retainedCount;
            if (removedCount == 0) {
                return 0;
            }
            if (current.revision == Long.MAX_VALUE) {
                throw new IllegalStateException("cache revision overflow");
            }

            RateSnapshot[] retainedEntries = new RateSnapshot[retainedCount];
            int index = 0;
            for (RateSnapshot entry : current.entries) {
                if (entry.expirationTime() > observationTime) {
                    retainedEntries[index++] = entry;
                }
            }

            state = new State(retainedEntries, current.revision + 1L);
            return removedCount;
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            State current = state;
            long observationTime = clock.now();
            return new Snapshot(current.entries, current.revision, observationTime);
        }
    }

    private static RateSnapshot find(RateSnapshot[] entries, String currency) {
        for (RateSnapshot entry : entries) {
            if (entry.currency().equals(currency)) {
                return entry;
            }
        }
        return null;
    }

    private static boolean contains(String[] currencies, String currency) {
        for (String candidate : currencies) {
            if (candidate.equals(currency)) {
                return true;
            }
        }
        return false;
    }

    private static void validateCurrency(String currency) {
        if (currency == null || currency.isEmpty()) {
            throw new IllegalArgumentException("currency must not be null or empty");
        }
    }

    private static void validateRate(double rate) {
        if (!isFinite(rate) || rate <= 0.0) {
            throw new IllegalArgumentException("rate must be positive and finite");
        }
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}

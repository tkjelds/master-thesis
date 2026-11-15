@TemporalForexCache.ThreadSafe
public final class TemporalForexCache {
    @interface ThreadSafe {}

    public interface Clock {
        long now();
    }

    public record Conversion(double value, long revision, long observationTime) {
    }

    public record RateSnapshot(String currency, double rate, long expiration) {
    }

    public record Snapshot(long revision, long observationTime, RateSnapshot[] entries) {
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

    private final Clock clock;
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
            throw new IllegalArgumentException("batch arrays must be nonempty and have matching lengths");
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

        synchronized (this) {
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("cache revision overflow");
            }

            long observationTime = clock.now();
            if (ttl > Long.MAX_VALUE - observationTime) {
                throw new IllegalArgumentException("expiration overflow");
            }
            long expiration = observationTime + ttl;

            int additions = 0;
            for (String currency : currencyCopy) {
                if (findCurrency(currencies, currency) < 0) {
                    additions++;
                }
            }
            if (additions > Integer.MAX_VALUE - this.currencies.length) {
                throw new IllegalStateException("cache capacity exceeded");
            }

            int newSize = this.currencies.length + additions;
            String[] newCurrencies = new String[newSize];
            double[] newRates = new double[newSize];
            long[] newExpirations = new long[newSize];

            System.arraycopy(this.currencies, 0, newCurrencies, 0, this.currencies.length);
            System.arraycopy(this.rates, 0, newRates, 0, this.rates.length);
            System.arraycopy(this.expirations, 0, newExpirations, 0, this.expirations.length);

            int next = this.currencies.length;
            for (int i = 0; i < currencyCopy.length; i++) {
                int index = findCurrency(newCurrencies, currencyCopy[i]);
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
        if (!Double.isFinite(amount) || amount < 0.0) {
            throw new IllegalArgumentException("amount must be finite and nonnegative");
        }

        double fromRate;
        double toRate;
        long capturedRevision;
        long observationTime;

        synchronized (this) {
            observationTime = clock.now();
            int fromIndex = findCurrency(currencies, from);
            int toIndex = findCurrency(currencies, to);

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
        synchronized (this) {
            long observationTime = clock.now();
            int removed = 0;
            for (long expiration : expirations) {
                if (expiration <= observationTime) {
                    removed++;
                }
            }
            if (removed == 0) {
                return 0;
            }
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("cache revision overflow");
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
        synchronized (this) {
            long observationTime = clock.now();
            RateSnapshot[] entries = new RateSnapshot[currencies.length];
            for (int i = 0; i < currencies.length; i++) {
                entries[i] = new RateSnapshot(currencies[i], rates[i], expirations[i]);
            }
            return new Snapshot(revision, observationTime, entries);
        }
    }

    private static int findCurrency(String[] names, String currency) {
        for (int i = 0; i < names.length; i++) {
            if (currency.equals(names[i])) {
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
        if (!Double.isFinite(rate) || rate <= 0.0) {
            throw new IllegalArgumentException("rate must be finite and positive");
        }
    }
}

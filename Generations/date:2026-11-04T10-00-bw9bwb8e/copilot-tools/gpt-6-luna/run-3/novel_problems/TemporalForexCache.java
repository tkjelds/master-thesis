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

    public record Snapshot(long revision, long observationTime, RateEntry[] entries) {
        public Snapshot {
            entries = entries.clone();
        }

        @Override
        public RateEntry[] entries() {
            return entries.clone();
        }
    }

    private static final class RateState {
        private final String currency;
        private final double rate;
        private final long expirationTime;

        private RateState(String currency, double rate, long expirationTime) {
            this.currency = currency;
            this.rate = rate;
            this.expirationTime = expirationTime;
        }
    }

    private final Object monitor = new Object();
    private final Clock clock;
    private RateState[] rates = new RateState[0];
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

    public void updateRatesBatch(String[] currencies, double[] newRates, long ttl) {
        if (currencies == null || newRates == null) {
            throw new IllegalArgumentException("batch arrays must not be null");
        }

        String[] currencyCopy = currencies.clone();
        double[] rateCopy = newRates.clone();

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
            long expirationTime = observationTime + ttl;

            int addedCount = 0;
            for (String currency : currencyCopy) {
                if (findRate(rates, currency) < 0) {
                    addedCount++;
                }
            }
            if (rates.length > Integer.MAX_VALUE - addedCount) {
                throw new ArithmeticException("too many currencies");
            }

            RateState[] updatedRates = new RateState[rates.length + addedCount];
            RateState[] replacements = new RateState[currencyCopy.length];
            for (int i = 0; i < currencyCopy.length; i++) {
                replacements[i] = new RateState(currencyCopy[i], rateCopy[i], expirationTime);
            }

            for (int i = 0; i < rates.length; i++) {
                int replacementIndex = findRate(currencyCopy, rates[i].currency);
                updatedRates[i] = replacementIndex < 0 ? rates[i] : replacements[replacementIndex];
            }

            int nextIndex = rates.length;
            for (int i = 0; i < currencyCopy.length; i++) {
                if (findRate(rates, currencyCopy[i]) < 0) {
                    updatedRates[nextIndex++] = replacements[i];
                }
            }

            rates = updatedRates;
            revision++;
        }
    }

    public Conversion convert(String from, String to, double amount) {
        validateCurrency(from);
        validateCurrency(to);
        if (amount < 0.0 || Double.isNaN(amount) || Double.isInfinite(amount)) {
            throw new IllegalArgumentException("amount must be finite and nonnegative");
        }

        double fromRate;
        double toRate;
        long capturedRevision;
        long observationTime;

        synchronized (monitor) {
            observationTime = clock.now();
            int fromIndex = findRate(rates, from);
            int toIndex = findRate(rates, to);
            if (fromIndex < 0 || toIndex < 0
                    || rates[fromIndex].expirationTime <= observationTime
                    || rates[toIndex].expirationTime <= observationTime) {
                throw new IllegalStateException("missing or expired currency rate");
            }

            fromRate = rates[fromIndex].rate;
            toRate = rates[toIndex].rate;
            capturedRevision = revision;
        }

        return new Conversion(amount / fromRate * toRate, capturedRevision, observationTime);
    }

    public int prune() {
        synchronized (monitor) {
            long observationTime = clock.now();
            int removedCount = 0;
            for (RateState rate : rates) {
                if (rate.expirationTime <= observationTime) {
                    removedCount++;
                }
            }
            if (removedCount == 0) {
                return 0;
            }
            if (revision == Long.MAX_VALUE) {
                throw new ArithmeticException("revision overflow");
            }

            RateState[] retainedRates = new RateState[rates.length - removedCount];
            int nextIndex = 0;
            for (RateState rate : rates) {
                if (rate.expirationTime > observationTime) {
                    retainedRates[nextIndex++] = rate;
                }
            }

            rates = retainedRates;
            revision++;
            return removedCount;
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            long observationTime = clock.now();
            RateEntry[] entries = new RateEntry[rates.length];
            for (int i = 0; i < rates.length; i++) {
                RateState rate = rates[i];
                entries[i] = new RateEntry(rate.currency, rate.rate, rate.expirationTime);
            }
            return new Snapshot(revision, observationTime, entries);
        }
    }

    private static void validateCurrency(String currency) {
        if (currency == null || currency.isEmpty()) {
            throw new IllegalArgumentException("currency must not be null or empty");
        }
    }

    private static void validateRate(double rate) {
        if (!(rate > 0.0) || Double.isNaN(rate) || Double.isInfinite(rate)) {
            throw new IllegalArgumentException("rate must be positive and finite");
        }
    }

    private static int findRate(RateState[] rates, String currency) {
        for (int i = 0; i < rates.length; i++) {
            if (rates[i].currency.equals(currency)) {
                return i;
            }
        }
        return -1;
    }

    private static int findRate(String[] currencies, String currency) {
        for (int i = 0; i < currencies.length; i++) {
            if (currencies[i].equals(currency)) {
                return i;
            }
        }
        return -1;
    }
}

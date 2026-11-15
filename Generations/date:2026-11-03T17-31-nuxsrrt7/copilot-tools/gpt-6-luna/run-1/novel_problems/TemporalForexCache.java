@TemporalForexCache.ThreadSafe
public final class TemporalForexCache {
    @interface ThreadSafe {}

    public interface Clock {
        long now();
    }

    public record Conversion(double value, long revision, long observationTime) {
    }

    public record RateEntry(String currency, double rate, long expiration) {
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
            RateEntry[] copy = new RateEntry[entries.length];
            for (int i = 0; i < entries.length; i++) {
                copy[i] = entries[i];
            }
            return copy;
        }

        public int size() {
            return entries.length;
        }

        public RateEntry entryAt(int index) {
            return entries[index];
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
        if (currencies == null || rates == null) {
            throw new IllegalArgumentException("batch arrays must not be null");
        }
        if (currencies.length == 0 || currencies.length != rates.length) {
            throw new IllegalArgumentException("batch arrays must be nonempty and have equal lengths");
        }

        String[] currencyCopy = new String[currencies.length];
        double[] rateCopy = new double[rates.length];
        for (int i = 0; i < currencies.length; i++) {
            currencyCopy[i] = currencies[i];
            rateCopy[i] = rates[i];
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
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("cache revision overflow");
            }

            long observationTime = clock.now();
            validateObservationTime(observationTime);
            if (ttl > Long.MAX_VALUE - observationTime) {
                throw new IllegalArgumentException("expiration time overflow");
            }
            long expiration = observationTime + ttl;

            int newEntries = 0;
            for (int i = 0; i < currencyCopy.length; i++) {
                if (findCurrency(currencyCopy[i]) < 0) {
                    newEntries++;
                }
            }
            if (newEntries > Integer.MAX_VALUE - size) {
                throw new IllegalStateException("cache capacity overflow");
            }

            int[] positions = new int[currencyCopy.length];
            for (int i = 0; i < currencyCopy.length; i++) {
                positions[i] = findCurrency(currencyCopy[i]);
            }

            ensureCapacity(size + newEntries);
            for (int i = 0; i < currencyCopy.length; i++) {
                int position = positions[i];
                if (position < 0) {
                    position = size++;
                    currencies[position] = currencyCopy[i];
                }
                this.rates[position] = rateCopy[i];
                expirations[position] = expiration;
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
        long capturedRevision;
        long observationTime;

        synchronized (monitor) {
            observationTime = clock.now();
            validateObservationTime(observationTime);

            int fromIndex = findCurrency(from);
            int toIndex = findCurrency(to);
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
            validateObservationTime(observationTime);

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

            int write = 0;
            for (int read = 0; read < size; read++) {
                if (expirations[read] > observationTime) {
                    if (write != read) {
                        currencies[write] = currencies[read];
                        rates[write] = rates[read];
                        expirations[write] = expirations[read];
                    }
                    write++;
                }
            }
            for (int i = write; i < size; i++) {
                currencies[i] = null;
                rates[i] = 0.0;
                expirations[i] = 0L;
            }
            size = write;
            revision++;
            return removed;
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            long observationTime = clock.now();
            validateObservationTime(observationTime);

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

        int capacity = currencies.length == 0 ? 4 : currencies.length;
        while (capacity < required) {
            if (capacity > Integer.MAX_VALUE / 2) {
                capacity = Integer.MAX_VALUE;
            } else {
                capacity *= 2;
            }
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
        if (currency == null || currency.length() == 0) {
            throw new IllegalArgumentException("currency must not be null or empty");
        }
    }

    private static void validateRate(double rate) {
        if (Double.isNaN(rate) || Double.isInfinite(rate) || rate <= 0.0) {
            throw new IllegalArgumentException("rate must be positive and finite");
        }
    }

    private static void validateObservationTime(long observationTime) {
        if (observationTime < 0) {
            throw new IllegalStateException("clock returned a negative time");
        }
    }
}

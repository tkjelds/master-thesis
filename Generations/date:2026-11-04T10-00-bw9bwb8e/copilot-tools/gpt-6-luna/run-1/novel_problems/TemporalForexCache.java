@TemporalForexCache.ThreadSafe
public final class TemporalForexCache {
    @interface ThreadSafe {}

    public interface Clock {
        long now();
    }

    public record Conversion(double value, long revision, long observationTime) {
    }

    public record Entry(String currency, double rate, long expiration) {
    }

    public record Snapshot(Entry[] entries, long revision, long observationTime) {
        public Snapshot {
            if (entries == null) {
                throw new IllegalArgumentException("entries must not be null");
            }
            Entry[] copy = new Entry[entries.length];
            for (int i = 0; i < entries.length; i++) {
                copy[i] = entries[i];
            }
            entries = copy;
        }

        @Override
        public Entry[] entries() {
            Entry[] copy = new Entry[entries.length];
            for (int i = 0; i < entries.length; i++) {
                copy[i] = entries[i];
            }
            return copy;
        }
    }

    private final Object monitor = new Object();
    private final Clock clock;

    private String[] currencies = new String[0];
    private double[] rates = new double[0];
    private long[] expirations = new long[0];
    private int count;
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
        if (currencies == null || rates == null || currencies.length != rates.length
                || currencies.length == 0) {
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
            long observationTime = clock.now();
            if (ttl > Long.MAX_VALUE - observationTime) {
                throw new IllegalArgumentException("expiration overflow");
            }
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision overflow");
            }

            long expiration = observationTime + ttl;
            int[] indices = new int[currencyCopy.length];
            int newCurrencies = 0;
            for (int i = 0; i < currencyCopy.length; i++) {
                int index = findCurrency(currencyCopy[i]);
                if (index < 0) {
                    index = count + newCurrencies;
                    newCurrencies++;
                }
                indices[i] = index;
            }

            if (newCurrencies > Integer.MAX_VALUE - count) {
                throw new IllegalStateException("cache capacity overflow");
            }
            int required = count + newCurrencies;
            if (required > this.currencies.length) {
                int newCapacity = this.currencies.length == 0 ? 4 : this.currencies.length;
                while (newCapacity < required) {
                    if (newCapacity > Integer.MAX_VALUE / 2) {
                        newCapacity = required;
                        break;
                    }
                    newCapacity *= 2;
                }

                String[] expandedCurrencies = new String[newCapacity];
                double[] expandedRates = new double[newCapacity];
                long[] expandedExpirations = new long[newCapacity];
                for (int i = 0; i < count; i++) {
                    expandedCurrencies[i] = this.currencies[i];
                    expandedRates[i] = this.rates[i];
                    expandedExpirations[i] = this.expirations[i];
                }
                this.currencies = expandedCurrencies;
                this.rates = expandedRates;
                this.expirations = expandedExpirations;
            }

            for (int i = 0; i < currencyCopy.length; i++) {
                int index = indices[i];
                this.currencies[index] = currencyCopy[i];
                this.rates[index] = rateCopy[i];
                this.expirations[index] = expiration;
            }
            count = required;
            revision++;
        }
    }

    public Conversion convert(String from, String to, double amount) {
        validateCurrency(from);
        validateCurrency(to);
        if (!(amount >= 0.0) || amount > Double.MAX_VALUE) {
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
                throw new IllegalStateException("missing currency rate");
            }

            fromRate = rates[fromIndex];
            toRate = rates[toIndex];
            fromExpiration = expirations[fromIndex];
            toExpiration = expirations[toIndex];
            capturedRevision = revision;

            if (fromExpiration <= observationTime || toExpiration <= observationTime) {
                throw new IllegalStateException("expired currency rate");
            }
        }

        double value = amount / fromRate * toRate;
        return new Conversion(value, capturedRevision, observationTime);
    }

    public int prune() {
        synchronized (monitor) {
            long observationTime = clock.now();
            int removed = 0;
            for (int i = 0; i < count; i++) {
                if (expirations[i] <= observationTime) {
                    removed++;
                }
            }
            if (removed == 0) {
                return 0;
            }
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision overflow");
            }

            int kept = 0;
            for (int i = 0; i < count; i++) {
                if (expirations[i] > observationTime) {
                    if (kept != i) {
                        currencies[kept] = currencies[i];
                        rates[kept] = rates[i];
                        expirations[kept] = expirations[i];
                    }
                    kept++;
                }
            }
            for (int i = kept; i < count; i++) {
                currencies[i] = null;
                rates[i] = 0.0;
                expirations[i] = 0L;
            }
            count = kept;
            revision++;
            return removed;
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            long observationTime = clock.now();
            Entry[] entries = new Entry[count];
            for (int i = 0; i < count; i++) {
                entries[i] = new Entry(currencies[i], rates[i], expirations[i]);
            }
            return new Snapshot(entries, revision, observationTime);
        }
    }

    private int findCurrency(String currency) {
        for (int i = 0; i < count; i++) {
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
        if (!(rate > 0.0) || rate > Double.MAX_VALUE) {
            throw new IllegalArgumentException("rate must be positive and finite");
        }
    }
}

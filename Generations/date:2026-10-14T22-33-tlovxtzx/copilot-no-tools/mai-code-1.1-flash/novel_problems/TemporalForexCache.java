@TemporalForexCache.ThreadSafe
public class TemporalForexCache {
    @interface ThreadSafe {}

    public interface Clock {
        long now();
    }

    public static final class Conversion {
        public final double value;
        public final long revision;
        public final long observationTime;

        public Conversion(double value, long revision, long observationTime) {
            this.value = value;
            this.revision = revision;
            this.observationTime = observationTime;
        }
    }

    public static final class SnapshotEntry {
        public final String currency;
        public final double rate;
        public final long expiration;

        public SnapshotEntry(String currency, double rate, long expiration) {
            this.currency = currency;
            this.rate = rate;
            this.expiration = expiration;
        }
    }

    public static final class Snapshot {
        public final SnapshotEntry[] entries;
        public final long revision;
        public final long observationTime;

        public Snapshot(SnapshotEntry[] entries, long revision, long observationTime) {
            if (entries == null) {
                throw new IllegalArgumentException("entries must not be null");
            }
            this.entries = entries.clone();
            this.revision = revision;
            this.observationTime = observationTime;
        }
    }

    private static final class ConvertCapture {
        public final double fromRate;
        public final double toRate;
        public final long revision;
        public final long observationTime;

        public ConvertCapture(double fromRate, double toRate, long revision, long observationTime) {
            this.fromRate = fromRate;
            this.toRate = toRate;
            this.revision = revision;
            this.observationTime = observationTime;
        }
    }

    private final Clock clock;
    private String[] currencies;
    private double[] rates;
    private long[] expirations;
    private long revision;

    public TemporalForexCache(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.clock = clock;
        this.currencies = new String[0];
        this.rates = new double[0];
        this.expirations = new long[0];
        this.revision = 0L;
    }

    public TemporalForexCache() {
        this(new Clock() {
            public long now() {
                return System.currentTimeMillis();
            }
        });
    }

    public void updateRate(String currency, double rate, long ttl) {
        validateCurrency(currency);
        validateRate(rate);
        if (ttl <= 0L) {
            throw new IllegalArgumentException("ttl must be positive");
        }

        synchronized (this) {
            long observationTime = clock.now();
            long expiration = checkedAdd(observationTime, ttl, "expiration overflow");
            if (this.revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision overflow");
            }
            long nextRevision = this.revision + 1L;

            int index = indexOf(this.currencies, currency);
            if (index >= 0) {
                double[] nextRates = this.rates.clone();
                long[] nextExpirations = this.expirations.clone();
                nextRates[index] = rate;
                nextExpirations[index] = expiration;
                this.rates = nextRates;
                this.expirations = nextExpirations;
            } else {
                String[] nextCurrencies = new String[this.currencies.length + 1];
                double[] nextRates = new double[this.rates.length + 1];
                long[] nextExpirations = new long[this.expirations.length + 1];

                System.arraycopy(this.currencies, 0, nextCurrencies, 0, this.currencies.length);
                System.arraycopy(this.rates, 0, nextRates, 0, this.rates.length);
                System.arraycopy(this.expirations, 0, nextExpirations, 0, this.expirations.length);

                nextCurrencies[nextCurrencies.length - 1] = currency;
                nextRates[nextRates.length - 1] = rate;
                nextExpirations[nextExpirations.length - 1] = expiration;

                this.currencies = nextCurrencies;
                this.rates = nextRates;
                this.expirations = nextExpirations;
            }

            this.revision = nextRevision;
        }
    }

    public void updateRatesBatch(String[] currencies, double[] rates, long ttl) {
        if (currencies == null || rates == null) {
            throw new IllegalArgumentException("currencies and rates must not be null");
        }
        if (currencies.length == 0 || rates.length == 0 || currencies.length != rates.length) {
            throw new IllegalArgumentException("currencies and rates must be non-empty and match in length");
        }
        if (ttl <= 0L) {
            throw new IllegalArgumentException("ttl must be positive");
        }

        String[] names = currencies.clone();
        double[] values = rates.clone();

        for (int i = 0; i < names.length; i++) {
            validateCurrency(names[i]);
            validateRate(values[i]);
        }

        for (int i = 0; i < names.length; i++) {
            for (int j = i + 1; j < names.length; j++) {
                if (names[i].equals(names[j])) {
                    throw new IllegalArgumentException("duplicate currency in batch");
                }
            }
        }

        synchronized (this) {
            long observationTime = clock.now();
            if (this.revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision overflow");
            }
            long nextRevision = this.revision + 1L;

            long[] expirationsForBatch = new long[names.length];
            for (int i = 0; i < names.length; i++) {
                expirationsForBatch[i] = checkedAdd(observationTime, ttl, "expiration overflow");
            }

            String[] mergedCurrencies = this.currencies.clone();
            double[] mergedRates = this.rates.clone();
            long[] mergedExpirations = this.expirations.clone();

            for (int i = 0; i < names.length; i++) {
                int index = indexOf(mergedCurrencies, names[i]);
                if (index >= 0) {
                    mergedRates[index] = values[i];
                    mergedExpirations[index] = expirationsForBatch[i];
                } else {
                    String[] nextCurrencies = new String[mergedCurrencies.length + 1];
                    double[] nextRates = new double[mergedRates.length + 1];
                    long[] nextExpirations = new long[mergedExpirations.length + 1];

                    System.arraycopy(mergedCurrencies, 0, nextCurrencies, 0, mergedCurrencies.length);
                    System.arraycopy(mergedRates, 0, nextRates, 0, mergedRates.length);
                    System.arraycopy(mergedExpirations, 0, nextExpirations, 0, mergedExpirations.length);

                    nextCurrencies[nextCurrencies.length - 1] = names[i];
                    nextRates[nextRates.length - 1] = values[i];
                    nextExpirations[nextExpirations.length - 1] = expirationsForBatch[i];

                    mergedCurrencies = nextCurrencies;
                    mergedRates = nextRates;
                    mergedExpirations = nextExpirations;
                }
            }

            this.currencies = mergedCurrencies;
            this.rates = mergedRates;
            this.expirations = mergedExpirations;
            this.revision = nextRevision;
        }
    }

    public Conversion convert(String from, String to, double amount) {
        validateCurrency(from);
        validateCurrency(to);
        validateAmount(amount);

        final ConvertCapture capture;
        synchronized (this) {
            long observationTime = clock.now();
            long revision = this.revision;

            double fromRate;
            double toRate;
            if (from.equals(to)) {
                fromRate = getValidRate(from, observationTime);
                toRate = fromRate;
            } else {
                fromRate = getValidRate(from, observationTime);
                toRate = getValidRate(to, observationTime);
            }

            capture = new ConvertCapture(fromRate, toRate, revision, observationTime);
        }

        double value = (amount / capture.fromRate) * capture.toRate;
        return new Conversion(value, capture.revision, capture.observationTime);
    }

    public int prune() {
        synchronized (this) {
            long observationTime = clock.now();
            int removeCount = 0;

            for (int i = 0; i < this.expirations.length; i++) {
                if (this.expirations[i] <= observationTime) {
                    removeCount++;
                }
            }

            if (removeCount == 0) {
                return 0;
            }

            String[] nextCurrencies = new String[this.currencies.length - removeCount];
            double[] nextRates = new double[this.rates.length - removeCount];
            long[] nextExpirations = new long[this.expirations.length - removeCount];

            int writeIndex = 0;
            for (int i = 0; i < this.expirations.length; i++) {
                if (this.expirations[i] > observationTime) {
                    nextCurrencies[writeIndex] = this.currencies[i];
                    nextRates[writeIndex] = this.rates[i];
                    nextExpirations[writeIndex] = this.expirations[i];
                    writeIndex++;
                }
            }

            this.currencies = nextCurrencies;
            this.rates = nextRates;
            this.expirations = nextExpirations;

            if (this.revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision overflow");
            }
            this.revision = this.revision + 1L;

            return removeCount;
        }
    }

    public Snapshot snapshot() {
        synchronized (this) {
            long observationTime = clock.now();
            SnapshotEntry[] entries = new SnapshotEntry[this.currencies.length];
            for (int i = 0; i < this.currencies.length; i++) {
                entries[i] = new SnapshotEntry(this.currencies[i], this.rates[i], this.expirations[i]);
            }
            return new Snapshot(entries, this.revision, observationTime);
        }
    }

    private double getValidRate(String currency, long observationTime) {
        int index = indexOf(this.currencies, currency);
        if (index < 0) {
            throw new IllegalStateException("missing rate for currency: " + currency);
        }
        if (this.expirations[index] <= observationTime) {
            throw new IllegalStateException("expired rate for currency: " + currency);
        }
        double rate = this.rates[index];
        if (!Double.isFinite(rate) || rate <= 0.0d) {
            throw new IllegalStateException("invalid rate for currency: " + currency);
        }
        return rate;
    }

    private static void validateCurrency(String currency) {
        if (currency == null || currency.length() == 0) {
            throw new IllegalArgumentException("currency must not be null or empty");
        }
    }

    private static void validateRate(double rate) {
        if (!Double.isFinite(rate) || rate <= 0.0d) {
            throw new IllegalArgumentException("rate must be a positive finite number");
        }
    }

    private static void validateAmount(double amount) {
        if (!Double.isFinite(amount) || amount < 0.0d) {
            throw new IllegalArgumentException("amount must be finite and non-negative");
        }
    }

    private static long checkedAdd(long base, long delta, String message) {
        if (delta > 0L && base > Long.MAX_VALUE - delta) {
            throw new IllegalArgumentException(message);
        }
        return base + delta;
    }

    private static int indexOf(String[] values, String target) {
        if (values == null || target == null) {
            return -1;
        }
        for (int i = 0; i < values.length; i++) {
            if (target.equals(values[i])) {
                return i;
            }
        }
        return -1;
    }
}

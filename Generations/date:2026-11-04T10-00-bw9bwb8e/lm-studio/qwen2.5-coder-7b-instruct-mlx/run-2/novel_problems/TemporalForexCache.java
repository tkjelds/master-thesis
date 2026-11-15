@TemporalForexCache.ThreadSafe
class TemporalForexCache {
    @interface ThreadSafe {}

    private static final long serialVersionUID = 1L;
    private static final String NULL_OR_EMPTY_ERROR = "null or empty";
    private static final String NEGATIVE_TTL_ERROR = "negative TTL";
    private static final String INVALID_RATE_ERROR = "invalid rate";

    interface Clock {
        long now();
    }

    private final Clock clock;
    private volatile CacheEntry[] cache;
    private volatile long revision;

    public TemporalForexCache(Clock clock) {
        this.clock = clock;
        this.cache = new CacheEntry[256]; // Assuming 256 unique currencies
        this.revision = 0;
    }

    public void updateRate(String currency, double rate, long ttl) {
        validateCurrency(currency);
        if (rate <= 0 || Double.isNaN(rate) || Double.isInfinite(rate)) {
            throw new IllegalArgumentException(INVALID_RATE_ERROR);
        }
        if (ttl <= 0) {
            throw new IllegalArgumentException(NEGATIVE_TTL_ERROR);
        }
        updateRatesBatch(new String[]{currency}, new double[]{rate}, ttl);
    }

    public void updateRatesBatch(String[] currencies, double[] rates, long ttl) {
        validateBatch(currencies, rates, ttl);
        long observationTime = clock.now();
        prune(observationTime);

        synchronized (this) {
            for (int i = 0; i < currencies.length; i++) {
                int index = hash(currencies[i]);
                cache[index] = new CacheEntry(rates[i], observationTime + ttl);
            }
            revision++;
        }
    }

    public Conversion convert(String from, String to, double amount) {
        validateCurrency(from);
        validateCurrency(to);
        if (amount < 0) {
            throw new IllegalArgumentException("negative amount");
        }

        long observationTime = clock.now();
        prune(observationTime);

        synchronized (this) {
            CacheEntry fromRateEntry = cache[hash(from)];
            CacheEntry toRateEntry = cache[hash(to)];

            if (fromRateEntry == null || observationTime >= fromRateEntry.expiration) {
                throw new IllegalStateException("expired rate for " + from);
            }
            if (toRateEntry == null || observationTime >= toRateEntry.expiration) {
                throw new IllegalStateException("expired rate for " + to);
            }

            double value = amount / fromRateEntry.rate * toRateEntry.rate;
            return new Conversion(value, revision, observationTime);
        }
    }

    public int prune() {
        long observationTime = clock.now();
        return prune(observationTime);
    }

    private int prune(long observationTime) {
        synchronized (this) {
            int count = 0;
            for (int i = 0; i < cache.length; i++) {
                if (cache[i] != null && observationTime >= cache[i].expiration) {
                    cache[i] = null;
                    count++;
                }
            }

            if (count > 0) {
                revision++;
            }

            return count;
        }
    }

    public CacheEntry[] snapshot() {
        long observationTime = clock.now();
        prune(observationTime);

        synchronized (this) {
            CacheEntry[] snapshotCache = new CacheEntry[cache.length];
            System.arraycopy(cache, 0, snapshotCache, 0, cache.length);
            return snapshotCache;
        }
    }

    private void validateCurrency(String currency) {
        if (currency == null || currency.isEmpty()) {
            throw new IllegalArgumentException(NULL_OR_EMPTY_ERROR);
        }
    }

    private void validateBatch(String[] currencies, double[] rates, long ttl) {
        if (currencies == null || rates == null || currencies.length != rates.length) {
            throw new IllegalArgumentException("mismatched or empty arrays");
        }
        for (String currency : currencies) {
            if (currency == null || currency.isEmpty()) {
                throw new IllegalArgumentException(NULL_OR_EMPTY_ERROR);
            }
        }
        for (double rate : rates) {
            if (rate <= 0 || Double.isNaN(rate) || Double.isInfinite(rate)) {
                throw new IllegalArgumentException(INVALID_RATE_ERROR);
            }
        }
        if (ttl <= 0) {
            throw new IllegalArgumentException(NEGATIVE_TTL_ERROR);
        }
    }

    private int hash(String currency) {
        return Math.abs(currency.hashCode()) % cache.length;
    }
}

@CacheEntry.ThreadSafe
class CacheEntry {
    @interface ThreadSafe {}

    final double rate;
    final long expiration;

    CacheEntry(double rate, long expiration) {
        this.rate = rate;
        this.expiration = expiration;
    }
}

@Conversion.ThreadSafe
class Conversion {
    @interface ThreadSafe {}

    final double value;
    final long revision;
    final long observationTime;

    Conversion(double value, long revision, long observationTime) {
        this.value = value;
        this.revision = revision;
        this.observationTime = observationTime;
    }
}

@TemporalForexCache.ThreadSafe
class TemporalForexCache {
    @interface ThreadSafe {}

    private static final double EPSILON = 1e-9;
    private static final long INF = Long.MAX_VALUE;

    private class Rate {
        double rate;
        long expiration;

        Rate(double rate, long expiration) {
            this.rate = rate;
            this.expiration = expiration;
        }
    }

    private final Clock clock;
    private final Object lock = new Object();
    private long revision = 0;
    private Rate[] rates;
    private long observationTime;

    public TemporalForexCache(int currencyCount, Clock clock) {
        this.clock = clock;
        this.rates = new Rate[currencyCount];
    }

    public void updateRate(String currency, double rate, long ttl) {
        synchronized (lock) {
            validateRate(currency, rate);
            observationTime = clock.now();
            int index = currencyIndex(currency);
            rates[index].rate = rate;
            rates[index].expiration = observationTime + ttl;
            revision++;
        }
    }

    public void updateRatesBatch(String[] currencies, double[] rates, long ttl) {
        synchronized (lock) {
            validateBatch(currencies, rates);
            observationTime = clock.now();
            for (int i = 0; i < currencies.length; i++) {
                rates[currencyIndex(currencies[i])].rate = rates[i];
                rates[currencyIndex(currencies[i])].expiration = observationTime + ttl;
            }
            revision++;
        }
    }

    public Conversion convert(String from, String to, double amount) {
        synchronized (lock) {
            validateConversion(from, to, amount);
            long now = clock.now();
            double fromRate = rates[currencyIndex(from)].rate;
            double toRate = rates[currencyIndex(to)].rate;

            if (now >= fromRate.expiration || now >= toRate.expiration) {
                throw new IllegalStateException("Expired rate");
            }

            double convertedAmount = amount / fromRate * toRate;

            return new Conversion(convertedAmount, revision, observationTime);
        }
    }

    public int prune() {
        synchronized (lock) {
            long now = clock.now();
            int removedCount = 0;
            for (int i = 0; i < rates.length; i++) {
                if (rates[i].expiration <= now) {
                    rates[i] = new Rate(INF, INF);
                    removedCount++;
                }
            }

            if (removedCount > 0) {
                revision++;
            }

            return removedCount;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            Rate[] ratesCopy = rates.clone();
            return new Snapshot(ratesCopy, revision, observationTime);
        }
    }

    private int currencyIndex(String currency) {
        return currency.hashCode() % rates.length;
    }

    private void validateRate(String currency, double rate) {
        if (currency == null || currency.isEmpty()) {
            throw new IllegalArgumentException("Invalid currency");
        }
        if (Double.isNaN(rate) || Double.isInfinite(rate)) {
            throw new IllegalArgumentException("Invalid rate");
        }
    }

    private void validateBatch(String[] currencies, double[] rates) {
        if (currencies == null || rates == null || lengthsDiffer(currencies, rates)) {
            throw new IllegalArgumentException("Invalid batch");
        }

        for (String currency : currencies) {
            if (currency == null || currency.isEmpty()) {
                throw new IllegalArgumentException("Invalid currency");
            }
        }

        for (double rate : rates) {
            if (Double.isNaN(rate) || Double.isInfinite(rate)) {
                throw new IllegalArgumentException("Invalid rate");
            }
        }

        for (int i = 1; i < currencies.length; i++) {
            if (currencies[i].equals(currencies[i - 1])) {
                throw new IllegalArgumentException("Duplicate currency in batch");
            }
        }
    }

    private boolean lengthsDiffer(String[] array1, double[] array2) {
        return array1.length != array2.length;
    }

    private void validateConversion(String from, String to, double amount) {
        if (from == null || from.isEmpty() || to == null || to.isEmpty()) {
            throw new IllegalArgumentException("Invalid currency");
        }
        if (Double.isNaN(amount) || Double.isInfinite(amount)) {
            throw new IllegalArgumentException("Invalid amount");
        }
    }

    public static class Conversion {
        final double value;
        final long revision;
        final long observationTime;

        Conversion(double value, long revision, long observationTime) {
            this.value = value;
            this.revision = revision;
            this.observationTime = observationTime;
        }
    }

    public static class Snapshot {
        final Rate[] rates;
        final long revision;
        final long observationTime;

        Snapshot(Rate[] rates, long revision, long observationTime) {
            this.rates = rates.clone();
            this.revision = revision;
            this.observationTime = observationTime;
        }
    }

    interface Clock {
        long now();
    }
}

@TemporalForexCache.ThreadSafe
class TemporalForexCache {
    @interface ThreadSafe {}

    private static final double EPSILON = 1e-9;
    private long revision = 0, observationTime = 0;
    private Rate[] rates = new Rate[26 * 256];
    private final Clock clock;

    public TemporalForexCache(Clock clock) {
        this.clock = clock;
    }

    public synchronized void updateRate(String currency, double rate, long ttl) {
        validateCurrency(currency);
        validateRate(rate);
        validateTTL(ttl);

        long expiration = clock.now() + ttl;
        int index = getIndex(currency);
        rates[index] = new Rate(rate, expiration);

        updateRevision();
    }

    public synchronized void updateRatesBatch(String[] currencies, double[] rates, long ttl) {
        validateCurrencies(currencies);
        validateRates(rates);
        validateTTL(ttl);

        long expiration = clock.now() + ttl;
        for (int i = 0; i < currencies.length; ++i) {
            int index = getIndex(currencies[i]);
            rates[index] = new Rate(rates[i], expiration);
        }

        updateRevision();
    }

    public synchronized Conversion convert(String from, String to, double amount) {
        validateCurrency(from);
        validateCurrency(to);
        validateAmount(amount);

        long now = clock.now();
        int fromIndex = getIndex(from), toIndex = getIndex(to);
        Rate fromRate = rates[fromIndex], toRate = rates[toIndex];

        if (fromRate == null || toRate == null) {
            throw new IllegalStateException("Invalid conversion rate");
        }

        if (fromRate.expiration <= now || toRate.expiration <= now) {
            throw new IllegalStateException("Expired conversion rate");
        }

        double converted = amount / fromRate.rate * toRate.rate;
        return new Conversion(converted, revision,observationTime);
    }

    public synchronized int prune() {
        long now = clock.now();
        int count = 0;
        for (int i = 0; i < rates.length; ++i) {
            if (rates[i] != null && rates[i].expiration <= now) {
                rates[i] = null;
                ++count;
            }
        }

        if (count > 0) {
            updateRevision();
        }
        return count;
    }

    public synchronized RatesSnapshot snapshot() {
        Rate[] copy = rates.clone();
        int index = -1;
        for (int i = 0; i < copy.length; ++i) {
            if (copy[i] != null && rates[i] == null) {
                index = i;
                break;
            }
        }

        return new RatesSnapshot(copy, revision,observationTime);
    }

    private synchronized void updateRevision() {
        ++revision;
        observationTime = clock.now();
    }

    private static void validateCurrency(String currency) {
        if (currency == null || currency.isEmpty()) {
            throw new IllegalArgumentException("Null or empty currency");
        }
    }

    private static void validateRate(double rate) {
        if (rate <= 0 || Double.isNaN(rate) || Double.isInfinite(rate)) {
            throw new IllegalArgumentException("Invalid rate");
        }
    }

    private static void validateAmount(double amount) {
        if (amount < 0 || Double.isNaN(amount) || Double.isInfinite(amount)) {
            throw new IllegalArgumentException("Negative or nonfinite amount");
        }
    }

    private static void validateTTL(long ttl) {
        if (ttl <= 0 || Long.compareUnsigned(ttl, observationTime - clock.now()) < 1) {
            throw new IllegalArgumentException("Nonpositive TTL");
        }
    }

    private static void validateCurrencies(String[] currencies) {
        if (currencies == null || currencies.length == 0) {
            throw new IllegalArgumentException("Null or empty currency array");
        }
    }

    private static void validateRates(double[] rates) {
        if (rates == null || rates.length != currencies.length) {
            throw new IllegalArgumentException("Mismatched currency and rate arrays");
        }
    }

    private static int getIndex(String currency) {
        return (currency.charAt(0) - 'A') * 256 + currency.charAt(1);
    }

    static class Rate {
        double rate, expiration;

        Rate(double rate, long expiration) {
            this.rate = rate;
            this.expiration = expiration;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Rate rate1 = (Rate) o;
            return Double.compare(rate1.rate, rate) == 0 &&
                    Double.compare(rate1.expiration, expiration) == 0;
        }

        @Override
        public int hashCode() {
            long temp = Double.doubleToLongBits(rate);
            return (int) (temp ^ (temp >>> 32)) + (int) (Double.doubleToLongBits(expiration) ^ (Double.doubleToLongBits(expiration) >>> 32));
        }
    }

    static class Conversion {
        double value;
        long revision,observationTime;

        Conversion(double value, long revision,long observationTime) {
            this.value = value;
            this.revision = revision;
            this.observationTime = observationTime;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Conversion conversion = (Conversion) o;
            return Double.compare(conversion.value, value) == 0 &&
                    revision == conversion.revision &&
                    observationTime == conversion.observationTime;
        }

        @Override
        public int hashCode() {
            long temp = Double.doubleToLongBits(value);
            return (int) (temp ^ (temp >>> 32)) + (int) revision +
                    (int) observationTime;
        }
    }

    static class RatesSnapshot {
        Rate[] rates;
        long revision,observationTime;

        RatesSnapshot(Rate[] rates, long revision,long observationTime) {
            this.rates = rates;
            this.revision = revision;
            this.observationTime = observationTime;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            RatesSnapshot that = (RatesSnapshot) o;
            return revision == that.revision &&
                    observationTime == that.observationTime &&
                    java.util.Arrays.equals(rates, that.rates);
        }

        @Override
        public int hashCode() {
            return java.util.Arrays.hashCode(rates) + (int) revision +
                    (int) observationTime;
        }
    }
}

@Clock.ThreadSafe
class Clock {
    @interface ThreadSafe {}

    public long now() {
        return System.nanoTime();
    }
}

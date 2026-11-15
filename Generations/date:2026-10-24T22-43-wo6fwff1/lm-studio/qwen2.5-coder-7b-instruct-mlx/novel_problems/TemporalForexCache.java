```java
public class TemporalForexCache {
    private final Clock clock;
    private final Rates rates;
    private long revision;

    public TemporalForexCache(Clock clock) {
        this.clock = clock;
        this.rates = new Rates();
    }

    public void updateRate(String currency, double rate, long ttl) {
        synchronized (this) {
            validateCurrency(currency);
            validateRate(rate);
            validateTTL(ttl);

            rates.updatecurrency(currency, rate, Math.addExact(clock.now(), ttl));
        }
    }

    public void updateRatesBatch(String[] currencies, double[] rates, long ttl) {
        synchronized (this) {
            validateBatch(currencies, rates, ttl);

            for (int i = 0; i < currencies.length; i++) {
                rates.updatecurrency(currencies[i], rates[i], Math.addExact(clock.now(), ttl));
            }

            revision++;
        }
    }

    public Conversion convert(String from, String to, double amount) {
        synchronized (this) {
            validateCurrency(from);
            validateCurrency(to);
            validateAmount(amount);

            long observationTime = clock.now();
            double fromRate = rates.getrate(from, observationTime);
            double toRate = rates.getrate(to, observationTime);

            if (from.equals(to)) {
                return new Conversion(amount, revision, observationTime);
            }

            if (fromRate <= 0 || toRate <= 0) {
                throw new IllegalStateException("Invalid rate");
            }

            double value = (amount / fromRate) * toRate;
            return new Conversion(value, revision, observationTime);
        }
    }

    public int prune() {
        synchronized (this) {
            long currentTime = clock.now();
            int removedCount = rates.prune(currentTime);

            if (removedCount > 0) {
                revision++;
            }

            return removedCount;
        }
    }

    public Entries snapshot() {
        synchronized (this) {
            return new Entries(rates.snapshot(), revision, clock.now());
        }
    }

    private void validateCurrency(String currency) {
        if (currency == null || currency.isEmpty()) {
            throw new IllegalArgumentException("Invalid currency");
        }
    }

    private void validateRate(double rate) {
        if (Double.isNaN(rate) || Double.isInfinite(rate) || rate <= 0) {
            throw new IllegalArgumentException("Invalid rate");
        }
    }

    private void validateTTL(long ttl) {
        if (ttl <= 0) {
            throw new IllegalArgumentException("Invalid TTL");
        }
    }

    private void validateBatch(String[] currencies, double[] rates, long ttl) {
        if (currencies.length != rates.length || currencies.length == 0) {
            throw new IllegalArgumentException("Invalid batch");
        }

        boolean[] seen = new boolean[currencies.length];
        for (String currency : currencies) {
            if (currency == null || currency.isEmpty() || seen[currency.hashCode()) {
                throw new IllegalArgumentException("Invalid batch");
            }
            seen[currency.hashCode()] = true;
        }

        for (double rate : rates) {
            validateRate(rate);
        }

        validateTTL(ttl);
    }

    private void validateAmount(double amount) {
        if (Double.isNaN(amount) || Double.isInfinite(amount) || amount < 0) {
            throw new IllegalArgumentException("Invalid amount");
        }
    }

    private static class Rates {
        private final Rate[] rates;
        private int size;

        public Rates() {
            this.rates = new Rate[10];
            this.size = 0;
        }

        public synchronized void updatecurrency(String currency, double rate, long expiration) {
            for (int i = 0; i < size; i++) {
                if (rates[i].currency.equals(currency)) {
                    rates[i] = new Rate(currency, rate, expiration);
                    return;
                }
            }

            if (size == rates.length) {
                resize();
            }

            rates[size++] = new Rate(currency, rate, expiration);
        }

        public synchronized int prune(long currentTime) {
            int removedCount = 0;
            Rate[] newRates = new Rate[rates.length];
            int newSize = 0;

            for (Rate rate : rates) {
                if (rate.expiration > currentTime) {
                    newRates[newSize++] = rate;
                } else {
                    removedCount++;
                }
            }

            rates = newRates;
            size = newSize;

            return removedCount;
        }

        public synchronized double getrate(String currency, long observationTime) {
            for (int i = 0; i < size; i++) {
                if (rates[i].currency.equals(currency)) {
                    return rates[i].rate;
                }
            }

            return 0.0;
        }

        public synchronized Entries snapshot() {
            Rate[] snapshotRates = new Rate[size];
            System.arraycopy(rates, 0, snapshotRates, 0, size);
            return new Entries(snapshotRates, System.currentTimeMillis(), clock.now());
        }

        private synchronized void resize() {
            Rate[] newRates = new Rate[rates.length * 2];
            System.arraycopy(rates, 0, newRates, 0, size);
            rates = newRates;
        }

        private static class Rate {
            final String currency;
            final double rate;
            final long expiration;

            public Rate(String currency, double rate, long expiration) {
                this.currency = currency;
                this.rate = rate;
                this.expiration = expiration;
            }
        }
    }

    private static class Entries {
        final Rate[] rates;
        final long revision;
        final long observationTime;

        public Entries(Rate[] rates, long revision, long observationTime) {
            this.rates = rates;
            this.revision = revision;
            this.observationTime = observationTime;
        }
    }

    private static class Conversion {
        final double value;
        final long revision;
        final long observationTime;

        public Conversion(double value, long revision, long observationTime) {
            this.value = value;
            this.revision = revision;
            this.observationTime = observationTime;
        }
    }
}
```

```java
import java.io.Serializable;

public class TemporalForexCache {
    private static final double EPSILON = 1e-9;

    interface Clock {
        long now();
    }

    private final Clock clock;
    private final Rates rates;
    private final Expirations expirations;
    private volatile long revision;

    public TemporalForexCache(Clock clock) {
        this.clock = clock;
        this.rates = new Rates();
        this.expirations = new Expirations();
        this.revision = 0;
    }

    public synchronized void updateRate(String currency, double rate, long ttl) {
        checkParameters(currency, rate, ttl);

        rates.setRate(currency, rate);
        expirations.setExpiration(currency, clock.now() + ttl);
        revision++;
    }

    public synchronized void updateRatesBatch(String[] currencies, double[] rates, long ttl) {
        checkParameters(currencies, rates, ttl);
        int length = currencies.length;
        String[] batchCurrencies = new String[length];
        double[] batchRates = new double[length];

        System.arraycopy(currencies, 0, batchCurrencies, 0, length);
        System.arraycopy(rates, 0, batchRates, 0, length);

        updates.putAll(batchCurrencies, batchRates);
        expirations.setExpirations(batchCurrencies, clock.now() + ttl);

        rates.putAll(updates);
        expirations.setExpirations(batchCurrencies, clock.now() + ttl);

        updates.clear();
        revision++;
    }

    public Conversion convert(String from, String to, double amount) {
        checkParameters(from, to, amount);

        long now = clock.now();
        if (from.equals(to)) {
            return new Conversion(amount, revision, now);
        }

        double fromRate = rates.getRate(from);
        double toRate = rates.getRate(to);

        if (fromRate <= 0 || toRate <= 0) {
            throw new IllegalStateException("Invalid rate");
        }

        if (now >= expirations.getExpiration(from) || now >= expirations.getExpiration(to)) {
            throw new IllegalStateException("Expired rate");
        }

        double value = amount / fromRate * toRate;
        return new Conversion(value, revision, now);
    }

    public synchronized int prune() {
        long now = clock.now();
        int removedCount = 0;

        for (String currency : rates.keySet()) {
            long expiration = expirations.getExpiration(currency);

            if (now >= expiration) {
                rates.remove(currency);
                expirations.remove(currency);
                removedCount++;
            }
        }

        if (removedCount > 0) {
            revision++;
        }

        return removedCount;
    }

    public Snapshot snapshot() {
        long now = clock.now();
        RatesSnapshot ratesSnapshot = new RatesSnapshot();

        for (String currency : rates.keySet()) {
            long expiration = expirations.getExpiration(currency);
            if (now < expiration) {
                ratesSnapshot.put(currency, rates.getRate(currency));
            }
        }

        return new Snapshot(ratesSnapshot, revision, now);
    }

    private void checkParameters(String currency, double rate, long ttl) {
        if (currency == null || currency.isEmpty()) {
            throw new IllegalArgumentException("Invalid currency name");
        }

        if (Double.isNaN(rate) || Double.isInfinite(rate)) {
            throw new IllegalArgumentException("Invalid rate");
        }

        if (ttl <= 0) {
            throw new IllegalArgumentException("Nonpositive TTL");
        }
    }

    private void checkParameters(String[] currencies, double[] rates, long ttl) {
        if (currencies == null || rates == null || currencies.length != rates.length) {
            throw new IllegalArgumentException("Mismatched/empty arrays");
        }

        int length = currencies.length;
        String[] batchCurrencies = new String[length];
        double[] batchRates = new double[length];

        System.arraycopy(currencies, 0, batchCurrencies, 0, length);
        System.arraycopy(rates, 0, batchRates, 0, length);

        for (String currency : batchCurrencies) {
            if (currency == null || currency.isEmpty()) {
                throw new IllegalArgumentException("Invalid currency name");
            }
        }

        for (double rate : batchRates) {
            if (Double.isNaN(rate) || Double.isInfinite(rate)) {
                throw new IllegalArgumentException("Invalid rate");
            }
        }

        if (ttl <= 0) {
            throw new IllegalArgumentException("Nonpositive TTL");
        }
    }

    private static class RatesSnapshot implements Serializable {
        private final java.util.Map<String, Double> rates;

        public RatesSnapshot() {
            this.rates = new java.util.HashMap<>();
        }

        public void put(String currency, double rate) {
            rates.put(currency, rate);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof RatesSnapshot)) return false;

            RatesSnapshot snapshot = (RatesSnapshot) o;
            return rates.equals(snapshot.rates);
        }

        @Override
        public int hashCode() {
            return rates.hashCode();
        }
    }

    private static class Snapshot implements Serializable {
        private final RatesSnapshot rates;
        private final long revision;
        private final long observationTime;

        public Snapshot(RatesSnapshot rates, long revision, long observationTime) {
            this.rates = rates;
            this.revision = revision;
            this.observationTime = observationTime;
        }

        public RatesSnapshot getRates() {
            return rates;
        }

        public long getRevision() {
            return revision;
        }

        public long getObservationTime() {
            return observationTime;
        }
    }

    private static class Conversion implements Serializable {
        private final double value;
        private final long revision;
        private final long observationTime;

        public Conversion(double value, long revision, long observationTime) {
            this.value = value;
            this.revision = revision;
            this.observationTime = observationTime;
        }

        public double getValue() {
            return value;
        }

        public long getRevision() {
            return revision;
        }

        public long getObservationTime() {
            return observationTime;
        }
    }
}
```

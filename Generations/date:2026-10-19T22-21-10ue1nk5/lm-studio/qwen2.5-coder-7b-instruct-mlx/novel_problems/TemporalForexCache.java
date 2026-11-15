@TemporalForexCache.ThreadSafe
public class TemporalForexCache {
    @interface ThreadSafe {}

    private static final double EPSILON = 1e-9;
    private class RateEntry {
        double rate;
        long expiration;
        RateEntry(double rate, long expiration) {
            this.rate = rate;
            this.expiration = expiration;
        }
    }
    private final Clock clock;
    private volatile long revision = 0;
    private RateEntry[] rates;
    private long[] expirations;

    public TemporalForexCache(Clock clock, int capacity) {
        this.clock = clock;
        rates = new RateEntry[capacity];
        expirations = new long[capacity];
    }

    public synchronized void updateRate(String currency, double rate, long ttl) {
        checkInputs(currency, rate, ttl);
        int index = findIndex(currency);
        if (index == -1) {
            throw new IllegalArgumentException("Currency not found: " + currency);
        }
        rates[index] = new RateEntry(rate, clock.now() + ttl);
    }

    public synchronized void updateRatesBatch(String[] currencies, double[] rates, long ttl) {
        checkInputs(currencies, rates, ttl);
        int[] indices = new int[currencies.length];
        for (int i = 0; i < currencies.length; i++) {
            indices[i] = findIndex(currencies[i]);
            if (indices[i] == -1) {
                throw new IllegalArgumentException("Currency not found: " + currencies[i]);
            }
        }
        RateEntry[] newRates = Arrays.copyOf(rates, rates.length, RateEntry[].class);
        long[] newExpirations = indices.map(i -> clock.now() + ttl).toArray();
        for (int i = 0; i < indices.length; i++) {
            rates[indices[i]] = newRates[i];
            expirations[indices[i]] = newExpirations[i];
        }
    }

    public synchronized Conversion convert(String from, String to, double amount) {
        checkInputs(from, to, amount);
        if (from.equals(to)) return new Conversion(amount, revision, clock.now());
        int fromIndex = findIndex(from), toIndex = findIndex(to);
        if (fromIndex == -1 || toIndex == -1) {
            throw new IllegalStateException("Currency not found: " + (fromIndex == -1 ? from : to));
        }
        RateEntry fromRate = rates[fromIndex];
        RateEntry toRate = rates[toIndex];
        if (fromRate.expiration < clock.now() || toRate.expiration < clock.now()) {
            throw new IllegalStateException("Expired rate during conversion");
        }
        double convertedAmount = amount / fromRate.rate * toRate.rate;
        return new Conversion(convertedAmount, revision, clock.now());
    }

    public synchronized int prune() {
        long now = clock.now();
        int removedCount = 0;
        for (int i = 0; i < expirations.length; i++) {
            if (expirations[i] <= now) {
                expirations[i] = 0;
                rates[i] = null;
                removedCount++;
            }
        }
        if (removedCount > 0) {
            revision++;
        }
        return removedCount;
    }

    public synchronized Snapshot snapshot() {
        prune();
        int count = 0;
        for (RateEntry rate : rates) {
            if (rate != null) count++;
        }
        String[] currencies = new String[count];
        double[] ratesArray = new double[count];
        long observationTime = clock.now();
        for (int i = 0, j = 0; i < this.rates.length; i++) {
            RateEntry rate = this.rates[i];
            if (rate != null) {
                currencies[j] = String.valueOf(i);
                ratesArray[j++] = rate.rate;
            }
        }
        return new Snapshot(currencies, ratesArray, revision, observationTime);
    }

    private int findIndex(String currency) {
        for (int i = 0; i < rates.length; i++) {
            if (String.valueOf(i).equals(currency)) return i;
        }
        return -1;
    }

    private void checkInputs(String currency, double rate, long ttl) {
        if (currency == null || currency.isEmpty()) throw new IllegalArgumentException("Invalid currency name");
        if (rate <= EPSILON || !Double.isFinite(rate)) throw new IllegalArgumentException("Invalid rate");
        if (ttl <= 0) throw new IllegalArgumentException("Invalid TTL");
    }

    private void checkInputs(String[] currencies, double[] rates, long ttl) {
        if (currencies == null || rates == null || currencies.length != rates.length) throw new IllegalArgumentException("Mismatched currency and rate arrays");
        for (String currency : currencies) {
            if (currency == null || currency.isEmpty()) throw new IllegalArgumentException("Invalid currency name");
        }
        for (double rate : rates) {
            if (rate <= EPSILON || !Double.isFinite(rate)) throw new IllegalArgumentException("Invalid rate");
        }
        for (long expiration : expirations(clock.now() + ttl)) {
            if (expiration < clock.now()) throw new IllegalArgumentException("Invalid TTL");
        }
    }

    private long[] expirations(long now) {
        long[] exps = new long[expirations.length];
        for (int i = 0; i < expirations.length; i++) {
            exps[i] = Math.max(0, expirations[i] - now);
        }
        return exps;
    }

    public static class Conversion {
        final double value;
        final long revision;
        final long observationTime;

        public Conversion(double value, long revision, long observationTime) {
            this.value = value;
            this.revision = revision;
            this.observationTime = observationTime;
        }
    }

    public static class Snapshot {
        final String[] currencies;
        final double[] rates;
        final long revision;
        final long observationTime;

        public Snapshot(String[] currencies, double[] rates, long revision, long observationTime) {
            this.currencies = Arrays.copyOf(currencies, currencies.length);
            this.rates = Arrays.copyOf(rates, rates.length);
            this.revision = revision;
            this.observationTime = observationTime;
        }
    }

    public interface Clock {
        long now();
    }
}

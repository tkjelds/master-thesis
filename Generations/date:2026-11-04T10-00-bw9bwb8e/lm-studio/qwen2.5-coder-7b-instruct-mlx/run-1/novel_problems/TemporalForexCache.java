@TemporalForexCache.ThreadSafe
public class TemporalForexCache {
    @interface ThreadSafe {}

    private final Clock clock;
    private final Rate[] rates;
    private long revision;
    private long observationTime;

    public TemporalForexCache(Clock clock) {
        this.clock = clock;
        this.rates = new Rate[128];
        this.revision = 0;
        this.observationTime = clock.now();
    }

    public void updateRate(String currency, double rate, long ttl) throws IllegalArgumentException {
        if (currency == null || currency.isEmpty() || rate <= 0 || !Double.isFinite(rate) || ttl <= 0) {
            throw new IllegalArgumentException();
        }
        updateBatch(new String[]{currency}, new double[]{rate}, ttl);
    }

    public void updateRatesBatch(String[] currencies, double[] rates, long ttl) throws IllegalArgumentException {
        if (currencies == null || rates == null || currencies.length != rates.length || ttl <= 0) {
            throw new IllegalArgumentException();
        }
        updateBatch(currencies, rates, ttl);
    }

    private synchronized void updateBatch(String[] currencies, double[] rates, long ttl) {
        long now = clock.now();
        if (now + ttl < observationTime) {
            throw new IllegalArgumentException();
        }
        Rate[] newRates = rates.clone();
        for (int i = 0; i < currencies.length; i++) {
            if (!Double.isFinite(newRates[i]) || newRates[i] <= 0) {
                throw new IllegalArgumentException();
            }
        }
        for (int i = 0; i < currencies.length; i++) {
            int index = findIndex(currencies[i]);
            if (index == -1) {
                rates[index] = new Rate(now + ttl, newRates[i]);
            } else {
                rates[index].expiration = now + ttl;
                rates[index].rate = newRates[i];
            }
        }
        revision++;
        observationTime = now;
    }

    private int findIndex(String currency) {
        for (int i = 0; i < rates.length; i++) {
            if (rates[i] != null && rates[i].currency.equals(currency)) {
                return i;
            }
        }
        return -1;
    }

    public synchronized Conversion convert(String from, String to, double amount) throws IllegalStateException {
        if (from == null || from.isEmpty() || to == null || to.isEmpty() || amount < 0 || !Double.isFinite(amount)) {
            throw new IllegalArgumentException();
        }
        long now = clock.now();
        if (now < observationTime) {
            throw new IllegalStateException();
        }
        double fromRate = 1.0, toRate = 1.0;
        for (int i = 0; i < rates.length; i++) {
            if (rates[i] != null && rates[i].currency.equals(from)) {
                fromRate = rates[i].rate;
            }
            if (rates[i] != null && rates[i].currency.equals(to)) {
                toRate = rates[i].rate;
            }
        }
        return new Conversion(from, to, amount / fromRate * toRate, revision, now);
    }

    public synchronized int prune() {
        long now = clock.now();
        int count = 0;
        for (int i = 0; i < rates.length; i++) {
            if (rates[i] != null && rates[i].expiration <= now) {
                rates[i] = null;
                count++;
            }
        }
        if (count > 0) {
            revision++;
        }
        observationTime = now;
        return count;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(rates, revision, observationTime);
    }

    private static class Rate {
        String currency;
        long expiration;
        double rate;

        Rate(long expiration, double rate) {
            this.currency = generateUniqueCurrency(); // Simplified for example
            this.expiration = expiration;
            this.rate = rate;
        }
    }

    private static String generateUniqueCurrency() {
        return "C" + ThreadLocalRandom.current().nextInt(1000);
    }

    public static class Conversion {
        String from;
        String to;
        double value;
        long revision;
        long observationTime;

        Conversion(String from, String to, double value, long revision, long observationTime) {
            this.from = from;
            this.to = to;
            this.value = value;
            this.revision = revision;
            this.observationTime = observationTime;
        }
    }

    public static class Snapshot {
        Rate[] rates;
        long revision;
        long observationTime;

        Snapshot(Rate[] rates, long revision, long observationTime) {
            this.rates = rates.clone();
            this.revision = revision;
            this.observationTime = observationTime;
        }
    }

    public interface Clock {
        long now();
    }
}

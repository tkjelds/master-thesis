@TemporalForexCache.ThreadSafe
public class TemporalForexCache {
    @interface ThreadSafe {}

    private static final double EPSILON = 1e-9;

    // Define the Clock interface
    public interface Clock {
        long now();
    }

    private final Clock clock;
    private final Object mutex = new Object();
    private Rate[] rates;
    private long revision;

    public TemporalForexCache(Clock clock) {
        this.clock = clock;
        this.rates = new Rate[256]; // Assuming a maximum of 256 currencies
        this.revision = 0;
    }

    public void updateRate(String currency, double rate, long ttl) {
        if (currency == null || currency.isEmpty() || rate <= EPSILON || ttl <= 0) {
            throw new IllegalArgumentException();
        }

        synchronized (mutex) {
            int index = currency.hashCode() & 255; // Simplified hash function
            rates[index] = new Rate(rate, clock.now() + ttl);
        }
    }

    public void updateRatesBatch(String[] currencies, double[] rates, long ttl) {
        if (currencies == null || rates == null || currencies.length != rates.length
                || ttl <= 0) {
            throw new IllegalArgumentException();
        }

        Rate[] newRates = copyAndValidateCurrencies(rates, currency -> currency != null && !currency.isEmpty());
        for (int i = 0; i < newRates.length; i++) {
            if (newRates[i] == null) {
                throw new IllegalArgumentException();
            }
        }

        synchronized (mutex) {
            for (int i = 0; i < newRates.length; i++) {
                int index = currencies[i].hashCode() & 255;
                rates[index] = newRates[i];
            }
        }
    }

    public Conversion convert(String from, String to, double amount) {
        if (from == null || from.isEmpty() || to == null || to.isEmpty()
                || amount <= EPSILON) {
            throw new IllegalArgumentException();
        }

        synchronized (mutex) {
            long observationTime = clock.now();
            double fromRate = getRate(from, observationTime);
            double toRate = getRate(to, observationTime);

            if (from.equals(to)) {
                return new Conversion(amount, revision, observationTime);
            }

            if (fromRate == 0 || toRate == 0) {
                throw new IllegalStateException();
            }

            double convertedAmount = amount / fromRate * toRate;
            return new Conversion(convertedAmount, revision, observationTime);
        }
    }

    public long prune() {
        synchronized (mutex) {
            long now = clock.now();
            int removedCount = 0;

            for (int i = 0; i < rates.length; i++) {
                if (rates[i] != null && rates[i].expiration <= now) {
                    removedCount++;
                    rates[i] = null;
                }
            }

            revision += removedCount;

            return removedCount;
        }
    }

    public Snapshot snapshot() {
        synchronized (mutex) {
            Rate[] copiedRates = copyArray(rates);
            long observationTime = clock.now();

            return new Snapshot(copiedRates, revision, observationTime);
        }
    }

    private double getRate(String currency, long observationTime) {
        int index = currency.hashCode() & 255;
        Rate rate = rates[index];

        if (rate != null && rate.expiration > observationTime) {
            return rate.rate;
        }

        return 0;
    }

    private Rate[] copyAndValidateCurrencies(double[] rates, Predicate<String> predicate) {
        int count = 0;

        for (double rate : rates) {
            if (!predicate.test(currency)) {
                throw new IllegalArgumentException();
            }

            count++;
        }

        Rate[] newRates = new Rate[count];
        int i = 0;

        for (int j = 0; j < rates.length; j++) {
            if (predicate.test(currency)) {
                newRates[i++] = new Rate(rates[j], Long.MAX_VALUE); // Set expiration to non-expired
            }
        }

        return newRates;
    }

    private Rate[] copyArray(Rate[] array) {
        Rate[] copiedArray = new Rate[array.length];

        for (int i = 0; i < array.length; i++) {
            copiedArray[i] = array[i];
        }

        return copiedArray;
    }

    private static class Rate {
        double rate;
        long expiration;

        public Rate(double rate, long expiration) {
            this.rate = rate;
            this.expiration = expiration;
        }
    }

    public static class Conversion {
        double value;
        long revision;
        long observationTime;

        public Conversion(double value, long revision, long observationTime) {
            this.value = value;
            this.revision = revision;
            this.observationTime = observationTime;
        }
    }

    public static class Snapshot {
        Rate[] entries;
        long revision;
        long observationTime;

        public Snapshot(Rate[] entries, long revision, long observationTime) {
            this.entries = entries;
            this.revision = revision;
            this.observationTime = observationTime;
        }
    }
}

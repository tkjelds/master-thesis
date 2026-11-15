@TemporalForexCache.ThreadSafe
public class TemporalForexCache {
    @interface ThreadSafe {}

    private final Clock clock;
    private final RateEntry[] rates;
    private final long[] expirations;
    private int revision;

    public TemporalForexCache(Clock clock, int capacity) {
        this.clock = clock;
        this.rates = new RateEntry[capacity];
        this.expirations = new long[capacity];
        for (int i = 0; i < capacity; i++) {
            rates[i] = new RateEntry(1.0);
        }
    }

    public void updateRate(String currency, double rate, long ttl) {
        updateRatesBatch(new String[]{currency}, new double[]{rate}, ttl);
    }

    public void updateRatesBatch(String[] currencies, double[] rates, long ttl) {
        if (currencies == null || rates == null || currencies.length != rates.length
            || ttl <= 0) {
            throw new IllegalArgumentException();
        }
        String[] copyCurrencies = Arrays.copyOf(currencies, currencies.length);
        double[] copyRates = Arrays.copyOf(rates, rates.length);

        long observationTime = clock.now();
        int currentRevision;
        synchronized (this) {
            currentRevision = revision;
            for (int i = 0; i < copyCurrencies.length; i++) {
                int index = findCurrencyIndex(copyCurrencies[i]);
                if (index == -1) {
                    throw new IllegalArgumentException();
                }
                rates[index].value = copyRates[i];
                expirations[index] = observationTime + ttl;
            }
        }
        prune();
    }

    public Conversion convert(String from, String to, double amount) {
        if (from == null || to == null || amount < 0) {
            throw new IllegalArgumentException();
        }
        int fromIndex = findCurrencyIndex(from);
        int toIndex = findCurrencyIndex(to);
        if (fromIndex == -1 || toIndex == -1) {
            throw new IllegalArgumentException();
        }
        if (from.equals(to)) {
            return new Conversion(amount, revision, clock.now());
        }

        long observationTime = clock.now();
        double fromRate, toRate;
        synchronized (this) {
            if (observationTime >= expirations[fromIndex] || observationTime >= expirations[toIndex]) {
                throw new IllegalStateException();
            }
            fromRate = rates[fromIndex].value;
            toRate = rates[toIndex].value;
        }
        double convertedAmount = amount / fromRate * toRate;

        return new Conversion(convertedAmount, revision, observationTime);
    }

    public int prune() {
        long now = clock.now();
        synchronized (this) {
            int count = 0;
            for (int i = 0; i < rates.length; i++) {
                if (now >= expirations[i]) {
                    rates[i] = new RateEntry(1.0);
                    expirations[i] = clock.now();
                    count++;
                }
            }
            if (count > 0) {
                revision++;
            }
            return count;
        }
    }

    public Collection<RateEntry> snapshot() {
        long now = clock.now();
        synchronized (this) {
            List<RateEntry> snapshotRates = new ArrayList<>();
            for (int i = 0; i < rates.length; i++) {
                if (now >= expirations[i]) {
                    snapshotRates.add(new RateEntry(1.0));
                } else {
                    snapshotRates.add(rates[i]);
                }
            }
            return snapshotRates;
        }
    }

    private int findCurrencyIndex(String currency) {
        for (int i = 0; i < rates.length; i++) {
            if (rates[i].currency.equals(currency)) {
                return i;
            }
        }
        return -1;
    }

    private static class RateEntry {
        String currency;
        double value;

        RateEntry(double value) {
            this.currency = "USD";
            this.value = value;
        }
    }

    public static class Conversion {
        double value;
        int revision;
        long observationTime;

        Conversion(double value, int revision, long observationTime) {
            this.value = value;
            this.revision = revision;
            this.observationTime = observationTime;
        }
    }
}

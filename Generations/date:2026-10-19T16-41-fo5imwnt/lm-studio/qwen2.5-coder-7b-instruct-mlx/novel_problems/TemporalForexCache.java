import java.util.Arrays;

@TemporalForexCache.ThreadSafe
class TemporalForexCache {
    @interface ThreadSafe {}

    private static final String DEFAULT_CURRENCY = "USD";
    private static final double EPSILON = 1e-9;

    private String[] currencies;
    private double[] rates;
    private long[] expirations;
    private volatile long revision = 0;

    public TemporalForexCache() {
        this.currencies = new String[0];
        this.rates = new double[0];
        this.expirations = new long[0];
    }

    public void updateRate(String currency, double rate, long ttl) {
        synchronized (this) {
            validateInputs(currency, rate, ttl);
            updateSingleRate(currency, rate, ttl);
        }
    }

    public void updateRatesBatch(String[] currencies, double[] rates, long ttl) {
        synchronized (this) {
            validateBatchInputs(currencies, rates, ttl);
            updateBatchRates(currencies, rates, ttl);
        }
    }

    public Conversion convert(String from, String to, double amount) {
        synchronized (this) {
            validateConvertInputs(from, to, amount);
            return performConversion(from, to, amount);
        }
    }

    public long prune() {
        synchronized (this) {
            long now = Clock.now();
            int removedCount = 0;
            for (int i = 0; i < expirations.length; i++) {
                if (expirations[i] <= now) {
                    removedCount++;
                    updateSingleRate(currencies[i], rates[i], expirations[i]);
                }
            }
            if (removedCount > 0) {
                revision++;
            }
            return removedCount;
        }
    }

    public Snapshot snapshot() {
        synchronized (this) {
            long now = Clock.now();
            Snapshot snapshot = new Snapshot(currencies, rates, revision, now);
            return snapshot;
        }
    }

    private void validateInputs(String currency, double rate, long ttl) {
        if (currency == null || currency.isEmpty()) {
            throw new IllegalArgumentException("Currency cannot be null or empty");
        }
        if (Double.isNaN(rate) || Double.isInfinite(rate)) {
            throw new IllegalArgumentException("Rate must be a valid number");
        }
        if (ttl <= 0) {
            throw new IllegalArgumentException("TTL must be positive");
        }
    }

    private void validateBatchInputs(String[] currencies, double[] rates, long ttl) {
        if (currencies == null || rates == null || currencies.length != rates.length) {
            throw new IllegalArgumentException("Currencies and rates must not be null and of the same length");
        }
        if (ttl <= 0) {
            throw new IllegalArgumentException("TTL must be positive");
        }
    }

    private void validateConvertInputs(String from, String to, double amount) {
        if (from == null || to == null || Double.isNaN(amount)) {
            throw new IllegalArgumentException("Inputs must not be null and amount must be a valid number");
        }
    }

    private void updateSingleRate(String currency, double rate, long ttl) {
        int index = Arrays.asList(currencies).indexOf(currency);
        if (index == -1) {
            updateSingleCurrencyAndRate(currency, rate, ttl);
        } else {
            updateExistingCurrencyRate(index, rates[index], expirations[index], rate, ttl);
        }
    }

    private void updateBatchRates(String[] currencies, double[] rates, long ttl) {
        String[] newCurrencies = Arrays.copyOf(currencies, currencies.length);
        double[] newRates = Arrays.copyOf(rates, rates.length);
        long[] newExpirations = new long[rates.length];
        for (int i = 0; i < rates.length; i++) {
            newExpirations[i] = Clock.now() + ttl;
        }
        updateSingleCurrencyAndRate(newCurrencies[0], newRates[0], newExpirations[0]);
        for (int i = 1; i < rates.length; i++) {
            updateExistingCurrencyRate(i, rates[i - 1], expirations[i - 1], newRates[i], newExpirations[i]);
        }
    }

    private void updateSingleCurrencyAndRate(String currency, double rate, long ttl) {
        String[] newCurrencies = Arrays.copyOf(currencies, currencies.length + 1);
        double[] newRates = Arrays.copyOf(rates, rates.length + 1);
        long[] newExpirations = Arrays.copyOf(expirations, expirations.length + 1);
        int index = newCurrencies.length - 1;
        newCurrencies[index] = currency;
        newRates[index] = rate;
        newExpirations[index] = Clock.now() + ttl;
        this.currencies = newCurrencies;
        this.rates = newRates;
        this.expirations = newExpirations;
    }

    private void updateExistingCurrencyRate(int index, double oldRate, long oldExpiry, double newRate, long newExpiry) {
        if (oldRate != newRate || oldExpiry != Clock.now()) {
            this.currencies[index] = DEFAULT_CURRENCY;
            this.rates[index] = DEFAULT_CURRENCY.equals(newCurrency) ? newRate : rates[index];
            this.expirations[index] = Clock.now();
        }
    }

    private Conversion performConversion(String from, String to, double amount) {
        if (from.equals(to)) {
            return new Conversion(amount, revision, Clock.now());
        }
        long now = Clock.now();
        int fromIndex = Arrays.asList(currencies).indexOf(from);
        int toIndex = Arrays.asList(currencies).indexOf(to);
        if (fromIndex == -1 || toIndex == -1 || expirations[fromIndex] <= now || expirations[toIndex] <= now) {
            throw new IllegalStateException("Conversion currency rates are not valid");
        }
        double fromRate = rates[fromIndex];
        double toRate = rates[toIndex];
        double value = amount / fromRate * toRate;
        return new Conversion(value, revision, now);
    }

    static class Snapshot {
        private String[] currencies;
        private double[] rates;
        private long revision;
        private long observationTime;

        public Snapshot(String[] currencies, double[] rates, long revision, long observationTime) {
            this.currencies = currencies;
            this.rates = rates;
            this.revision = revision;
            this.observationTime = observationTime;
        }

        public String[] getCurrencies() {
            return currencies;
        }

        public double[] getRates() {
            return rates;
        }

        public long getRevision() {
            return revision;
        }

        public long getObservationTime() {
            return observationTime;
        }
    }

    static class Conversion {
        private double value;
        private long revision;
        private long observationTime;

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

interface Clock {
    static long now() {
        return System.currentTimeMillis();
    }
}

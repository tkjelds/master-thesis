@TemporalForexCache.ThreadSafe
public class TemporalForexCache {
    @interface ThreadSafe {}


    public interface Clock {
        long now();
    }

    private static class RateEntry {
        String currency;
        double rate;
        long expiration;

        RateEntry(String currency, double rate, long expiration) {
            this.currency = currency;
            this.rate = rate;
            this.expiration = expiration;
        }
    }

    public static class Conversion {
        public final double value;
        public final long revision;
        public final long observationTime;

        public Conversion(double value, long revision, long observationTime) {
            this.value = value;
            this.revision = revision;
            this.observationTime = observationTime;
        }
    }

    private final Clock clock;
    private long revision = 0;
    private final RateEntry[] rates;
    private int count = 0;

    public TemporalForexCache(Clock clock) {
        this.clock = clock;
        this.rates = new RateEntry[1024]; // Fixed capacity for simplicity
    }

    private void validate(String[] currencies, double[] rates_arr, long ttl) {
        if (ttl <= 0) throw new IllegalArgumentException();
        if (currencies == null || rates_arr == null || currencies.length != rates_arr.length) {
            throw new IllegalArgumentException();
        }
        for (int i = 0; i < currencies.length; i++) {
            if (currencies[i] == null || currencies[i].isEmpty()) {
                throw new IllegalArgumentException();
            }
            if (rates_arr[i] <= 0 || Double.isNaN(rates_arr[i]) || Double.isInfinite(rates_arr[i])) {
                throw new IllegalArgumentException();
            }
        }
    }

    public void updateRate(String currency, double rate, long ttl) {
        if (currency == null || currency.isEmpty()) throw new IllegalArgumentException();
        if (rate <= 0 || Double.isNaN(rate) || Double.isInfinite(rate)) throw new IllegalArgumentException();
        if (ttl <= 0) throw new IllegalArgumentException();

        synchronized (this) {
            long exp = clock.now() + ttl;
            if (exp < 0) throw new IllegalArgumentException("Overflow");
            for (int i = 0; i < count; i++) {
                if (rates[i].currency.equals(currency)) {
                    rates[i].rate = rate;
                    rates[i].expiration = exp;
                    return;
                }
            }
            if (count >= rates.length) throw new RuntimeException("Full");
            rates[count++] = new RateEntry(currency, rate, exp);
        }
    }

    public void updateRatesBatch(String[] currencies, double[] rates_arr, long ttl) {
        if (currencies == null || rates_arr == null || currencies.length != rates_arr.length) {
            throw new IllegalArgumentException();
        }
        if (ttl <= 0) throw new IllegalArgumentException();

        // Copy and validate inputs before locking
        String[] cCopy = new String[currencies.length];
        for (int i = 0; i < currencies.length; i++) {
            if (currencies[i] == null || currencies[i].isEmpty()) throw new IllegalArgumentException();
            cCopy[i] = currencies[i];
        }
        if (rates_arr_isValid(rates_arr)) {
            // Validation logic handled in check to avoid partial success
        }

        synchronized (this) {
            for (int i = 0; i < cCopy.length; i++) {
                if (rates_arr[i] <= 0 || Double.isNaN(rates_arr[i]) || Double.isInfinite(rates_arr[i])) {
                    throw new IllegalArgumentException();
                }
            }

            long expBase = clock.now() + ttl;
            if (expBase < 0) throw new IllegalArgumentException();

            for (int i = 0; i < cCopy.length; i++) {
                boolean found = false;
                for (int j = 0; j < count; j++) {
                    if (rates[j].currency.equals(cCopy[i])) {
                        rates[j].rate = rates_arr[i];
                        rates[j].expiration = expBase;
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    if (count < rates.length) {
                        rates[count++] = new RateEntry(cCopy[i], rates_arr[i], expBase);
                    } else {
                        throw new RuntimeException("Full");
                    }
                }
            }
            revision++;
        }
    }

    private boolean rates_arr_isValid(double[] arr) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] <= 0 || Double.isNaN(arr[i]) || Double.isInfinite(arr[i])) return false;
        }
        return true;
    }

    public Conversion convert(String from, String to, double amount) {
        if (from == null || to == null || from.isEmpty() || to.isEmpty()) throw new IllegalArgumentException();
        if (Double.isNaN(amount) || Double.isInfinite(amount)) throw new IllegalArgumentException();

        long obsTime;
        long currentRev;
        double fromRate;
        double toRate;

        synchronized (this) {
            obsTime = clock.now();
            currentRev = revision;
            
            if (from.equals(to)) {
                // Must still have a valid rate even if same currency
                for (int i = 0; i < count; i++) {
                    if (rates[i].currency.equals(from)) {
                        if (rates[i].expiration > obsTime) {
                            fromRate = rates[i].rate;
                        } else {
                            throw new IllegalStateException();
                        }
                        break;
                    }
                }
            } else {
                for (int i = 0; i < count; i++) {
                    if (rates[i].currency.equals(from)) {
                        if (rates[i].expiration > obsTime) {
                            fromRate = rates[i].rate;
                        } else {
                            throw new IllegalStateException();
                        }
                        break;
                    }
                }
                for (int i = 0; i < count; i++) {
                    if (rates[i].currency.equals(to)) {
                        if (rates[i].expiration > obsTime) {
                            toRate = rates[i].rate;
                        } else {
                            throw new IllegalStateException();
                        }
                        break;
                    }
                }
            }

            // Re-check/fetch logic inside to ensure we have both validly if different
            // Actually, the prompt says "requires a valid rate" even for same currency.
            // Let's ensure both are grabbed if different, or one exists if same.
        }

        // The requirement states "Capture both immutable rates, expirations, revision and time"
        // Since we need the rate values:
        synchronized (this) {
            obsTime = clock.now();
            currentRev = revision;

            // Re-fetch to satisfy "requires a valid rate" logic
            double r_from = -1;
            for (int i = 0; i < count; i++) {
                if (rates[i].currency.equals(from)) {
                    if (rates[i].expiration > obsTime) r_from = rates[i].rate;
                    else throw new IllegalStateException();
                    break;
                }
            }
            
            double r_to = -1;
            if (!from.equals(to)) {
                for (int i = 0; i < count; i++) {
                    if (rates[i].currency.equals(to)) {
                        if (rates[i].expiration > obsTime) r_to = rates[i].rate;
                        else throw new IllegalStateException();
                        break;
                    }
                }
            } else {
                r_to = r_from;
            }

            double result = (amount / r_from) * r_to;
            return new Conversion(result, currentRev, obsTime);
        }
    }

    public int prune() {
        long now = clock.now();
        int removed = 0;
        synchronized (this) {
            for (int i = 0; i < count; i++) {
                if (rates[i].expiration <= now) {
                    // Swap with last and decrement count
                    RateEntry temp = rates[i];
                    rates[i] = rates[count - 1];
                    rates[count - 1] = temp;
                    count--;
                    i--;
                    removed++;
                }
            }
            if (removed > 0) {
                revision++;
            }
            return removed;
        }
    }

    public RateEntry[] snapshot() {
        synchronized (this) {
            RateEntry[] copy = new RateEntry[count];
            for (int i = 0; i < count; i++) {
                copy[i] = new RateEntry(rates[i].currency, rates[i].rate, rates[i].expiration);
            }
            return copy;
        }
    }
}

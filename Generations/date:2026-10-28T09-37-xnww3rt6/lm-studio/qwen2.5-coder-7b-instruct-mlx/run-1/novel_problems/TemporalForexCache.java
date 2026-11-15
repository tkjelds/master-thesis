@TemporalForexCache.ThreadSafe
public class TemporalForexCache {
    @interface ThreadSafe {}

    private static final double EPSILON = 1e-9;
    private class Entry {
        volatile double rate;
        volatile long expiration;

        public Entry(double rate, long expiration) {
            this.rate = rate;
            this.expiration = expiration;
        }
    }

    private final Clock clock;
    private final Entry[] entries;
    private long revision;

    public TemporalForexCache(Clock clock, int capacity) {
        this.clock = clock;
        this.entries = new Entry[capacity];
        for (int i = 0; i < capacity; i++) {
            entries[i] = new Entry(1.0, 0);
        }
        this.revision = 0;
    }

    public void updateRate(String currency, double rate, long ttl) {
        if (currency == null || currency.isEmpty() || !Double.isFinite(rate) ||
            Double.compare(Math.abs(rate), 0.0, EPSILON) == 0 || ttl <= 0) {
            throw new IllegalArgumentException();
        }

        int index = Math.abs(currency.hashCode()) % entries.length;
        synchronized (entries[index]) {
            if (clock.now() + ttl >= Long.MAX_VALUE) {
                throw new ArithmeticException("Time overflow");
            }
            entries[index].rate = rate;
            entries[index].expiration = clock.now() + ttl;
        }
    }

    public void updateRatesBatch(String[] currencies, double[] rates, long ttl) {
        if (currencies == null || rates == null || currencies.length != rates.length ||
            ttl <= 0) {
            throw new IllegalArgumentException();
        }

        for (String currency : currencies) {
            if (currency == null || currency.isEmpty()) {
                throw new IllegalArgumentException();
            }
        }

        if (!Double.isFinite(ttl) || Double.compare(Math.abs(ttl), 0.0, EPSILON) == 0) {
            throw new IllegalArgumentException();
        }

        int[] indices = new int[currencies.length];
        for (int i = 0; i < currencies.length; i++) {
            indices[i] = Math.abs(currencies[i].hashCode()) % entries.length;
        }

        synchronized (this) {
            long newRevision = revision + 1;
            boolean changed = false;

            for (int i = 0; i < currencies.length; i++) {
                if (!Double.isFinite(rates[i]) || Double.compare(Math.abs(rates[i]), 0.0, EPSILON) == 0) {
                    throw new IllegalArgumentException();
                }

                synchronized (entries[indices[i]]) {
                    if (clock.now() + ttl >= Long.MAX_VALUE) {
                        throw new ArithmeticException("Time overflow");
                    }
                    if (entries[indices[i]].rate != rates[i] || entries[indices[i]].expiration < clock.now()) {
                        changed = true;
                        entries[indices[i]].rate = rates[i];
                        entries[indices[i]].expiration = clock.now() + ttl;
                    }
                }
            }

            if (changed) {
                revision = newRevision;
            }
        }
    }

    public Conversion convert(String from, String to, double amount) {
        if (from == null || from.isEmpty() || to == null || to.isEmpty() ||
            !Double.isFinite(amount) || Double.compare(Math.abs(amount), 0.0, EPSILON) < 0) {
            throw new IllegalArgumentException();
        }

        int fromIndex = Math.abs(from.hashCode()) % entries.length;
        int toIndex = Math.abs(to.hashCode()) % entries.length;

        synchronized (entries[fromIndex]) {
            if (entries[fromIndex].expiration <= clock.now()) {
                throw new IllegalStateException("Expired rate for currency " + from);
            }
        }

        if (from.equals(to)) {
            return new Conversion(amount, revision, clock.now());
        }

        synchronized (entries[toIndex]) {
            if (entries[toIndex].expiration <= clock.now()) {
                throw new IllegalStateException("Expired rate for currency " + to);
            }
        }

        double fromRate = entries[fromIndex].rate;
        double toRate = entries[toIndex].rate;

        return new Conversion(amount / fromRate * toRate, revision, clock.now());
    }

    public int prune() {
        int removed = 0;
        long now = clock.now();

        synchronized (this) {
            boolean changed = false;

            for (int i = 0; i < entries.length; i++) {
                synchronized (entries[i]) {
                    if (entries[i].expiration <= now) {
                        removed++;
                        changed = true;
                        entries[i] = new Entry(1.0, 0);
                    }
                }
            }

            if (changed) {
                revision++;
            }
        }

        return removed;
    }

    public Map<String, Double> snapshot() {
        synchronized (this) {
            Map<String, Double> result = new HashMap<>();
            long now = clock.now();

            for (int i = 0; i < entries.length; i++) {
                synchronized (entries[i]) {
                    if (entries[i].expiration > now) {
                        result.put(i + "", entries[i].rate);
                    }
                }
            }

            return result;
        }
    }

    public static class Conversion {
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

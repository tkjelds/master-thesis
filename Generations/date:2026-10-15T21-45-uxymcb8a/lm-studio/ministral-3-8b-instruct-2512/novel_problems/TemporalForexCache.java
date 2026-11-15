@TemporalForexCache.ThreadSafe
public class TemporalForexCache {
    @interface ThreadSafe {}

    private static final double MIN_RATE = 1e-6;
    private static final long MAX_TTL = Long.MAX_VALUE / 2;

    private volatile Clock clock;
    private final Object lock = new Object();
    private long revision = 0;
    private volatile Entry[] entries = new Entry[16];
    private int size = 0;

    public void setClock(Clock clock) {
        this.clock = clock;
    }

    private static class Entry {
        String currency;
        double rate;
        long expiration;

        Entry(String currency, double rate, long expiration) {
            this.currency = currency;
            this.rate = rate;
            this.expiration = expiration;
        }
    }

    public void updateRate(String currency, double rate, long ttl) {
        if (currency == null || currency.isEmpty() ||
            !Double.isFinite(rate) || rate <= 0 ||
            ttl <= 0 || ttl > MAX_TTL) {
            throw new IllegalArgumentException("Invalid arguments");
        }

        synchronized (lock) {
            long now = clock.now();
            Entry[] newEntries = Arrays.copyOf(entries, entries.length * 2);
            boolean found = false;

            for (int i = 0; i < size; i++) {
                if (entries[i].currency.equals(currency)) {
                    newEntries[i] = new Entry(currency, rate, now + ttl);
                    found = true;
                    break;
                }
            }

            if (!found) {
                newEntries[size] = new Entry(currency, rate, now + ttl);
                size++;
            }

            entries = newEntries;
            revision++;
        }
    }

    public void updateRatesBatch(String[] currencies, double[] rates, long ttl) {
        if (currencies == null || rates == null ||
            currencies.length != rates.length ||
            currencies.length == 0) {
            throw new IllegalArgumentException("Invalid batch");
        }

        for (int i = 0; i < currencies.length; i++) {
            if (currencies[i] == null || currencies[i].isEmpty() ||
                !Double.isFinite(rates[i]) || rates[i] <= 0) {
                throw new IllegalArgumentException("Invalid batch element");
            }
        }

        if (ttl <= 0 || ttl > MAX_TTL) {
            throw new IllegalArgumentException("Invalid TTL");
        }

        synchronized (lock) {
            long now = clock.now();
            Entry[] newEntries = Arrays.copyOf(entries, entries.length * 2);
            boolean[] updated = new boolean[currencies.length];

            for (int i = 0; i < currencies.length; i++) {
                String currency = currencies[i];
                double rate = rates[i];

                for (int j = 0; j < size; j++) {
                    if (entries[j].currency.equals(currency)) {
                        newEntries[j] = new Entry(currency, rate, now + ttl);
                        updated[i] = true;
                        break;
                    }
                }

                if (!updated[i]) {
                    newEntries[size] = new Entry(currency, rate, now + ttl);
                    size++;
                }
            }

            entries = newEntries;
            revision++;
        }
    }

    public Conversion convert(String from, String to, double amount) {
        if (from == null || from.isEmpty() ||
            to == null || to.isEmpty() ||
            !Double.isFinite(amount) || amount <= 0) {
            throw new IllegalArgumentException("Invalid arguments");
        }

        synchronized (lock) {
            long now = clock.now();
            double fromRate = -1, toRate = -1;

            for (Entry entry : entries) {
                if (entry != null && entry.expiration > now) {
                    if (entry.currency.equals(from)) fromRate = entry.rate;
                    if (entry.currency.equals(to)) toRate = entry.rate;

                    if (fromRate > 0 && toRate > 0) break;
                }
            }

            if (fromRate <= 0 || toRate <= 0) {
                throw new IllegalStateException("Invalid rate");
            }

            double converted = amount / fromRate * toRate;
            return new Conversion(converted, revision, now);
        }
    }

    public int prune() {
        synchronized (lock) {
            long now = clock.now();
            Entry[] newEntries = Arrays.copyOf(entries, entries.length);
            int removed = 0;

            for (int i = 0; i < size; i++) {
                if (entries[i].expiration <= now) {
                    removed++;
                } else {
                    newEntries[removed] = entries[i];
                }
            }

            if (removed > 0) {
                size -= removed;
                entries = Arrays.copyOf(newEntries, size);
                revision++;
            }

            return removed;
        }
    }

    public Entry[] snapshot() {
        synchronized (lock) {
            long now = clock.now();
            Entry[] snapshotEntries = new Entry[size];

            for (int i = 0; i < size; i++) {
                if (entries[i].expiration > now) {
                    snapshotEntries[i] = new Entry(entries[i].currency, entries[i].rate, entries[i].expiration);
                }
            }

            return snapshotEntries;
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

    private interface Clock {
        long now();
    }
}

@TemporalForexCache.ThreadSafe
public final class TemporalForexCache {
    @interface ThreadSafe {}

    public interface Clock {
        long now();
    }

    public static final class Conversion {
        public final double value;
        public final long revision;
        public final long observationTime;

        private Conversion(double value, long revision, long observationTime) {
            this.value = value;
            this.revision = revision;
            this.observationTime = observationTime;
        }
    }

    public static final class Entry {
        public final String currency;
        public final double rate;
        public final long expiration;

        private Entry(String currency, double rate, long expiration) {
            this.currency = currency;
            this.rate = rate;
            this.expiration = expiration;
        }
    }

    public static final class Snapshot {
        public final long revision;
        public final long observationTime;
        private final Entry[] entries;

        private Snapshot(Entry[] entries, long revision, long observationTime) {
            this.entries = entries;
            this.revision = revision;
            this.observationTime = observationTime;
        }

        public Entry[] getEntries() {
            Entry[] copy = new Entry[entries.length];
            for (int i = 0; i < entries.length; i++) {
                copy[i] = entries[i];
            }
            return copy;
        }
    }

    private final Object lock = new Object();
    private final Clock clock;
    private Entry[] entries = new Entry[0];
    private long revision;

    public TemporalForexCache(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("Clock must not be null");
        }
        this.clock = clock;
    }

    public void updateRate(String currency, double rate, long ttl) {
        updateRatesBatch(new String[] { currency }, new double[] { rate }, ttl);
    }

    public void updateRatesBatch(String[] currencies, double[] rates, long ttl) {
        if (currencies == null || rates == null
                || currencies.length == 0
                || currencies.length != rates.length) {
            throw new IllegalArgumentException("Invalid batch arrays");
        }
        if (ttl <= 0) {
            throw new IllegalArgumentException("TTL must be positive");
        }

        String[] namesCopy = currencies.clone();
        double[] ratesCopy = rates.clone();

        for (int i = 0; i < namesCopy.length; i++) {
            validateCurrency(namesCopy[i]);
            if (!(ratesCopy[i] > 0.0 && ratesCopy[i] <= Double.MAX_VALUE)) {
                throw new IllegalArgumentException("Rate must be positive and finite");
            }
            for (int j = 0; j < i; j++) {
                if (namesCopy[i].equals(namesCopy[j])) {
                    throw new IllegalArgumentException("Duplicate currency");
                }
            }
        }

        synchronized (lock) {
            long now = clock.now();
            if (now > Long.MAX_VALUE - ttl) {
                throw new IllegalStateException("Expiration overflow");
            }
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("Revision overflow");
            }
            long expiration = now + ttl;

            int newCurrencies = 0;
            for (int i = 0; i < namesCopy.length; i++) {
                if (find(entries, namesCopy[i]) < 0) {
                    newCurrencies++;
                }
            }

            long newLength = (long) entries.length + newCurrencies;
            if (newLength > Integer.MAX_VALUE) {
                throw new IllegalStateException("Cache capacity exceeded");
            }

            Entry[] replacement = new Entry[(int) newLength];
            for (int i = 0; i < entries.length; i++) {
                replacement[i] = entries[i];
            }

            int next = entries.length;
            for (int i = 0; i < namesCopy.length; i++) {
                int index = find(entries, namesCopy[i]);
                if (index < 0) {
                    index = next++;
                }
                replacement[index] = new Entry(namesCopy[i], ratesCopy[i], expiration);
            }

            entries = replacement;
            revision++;
        }
    }

    public Conversion convert(String from, String to, double amount) {
        validateCurrency(from);
        validateCurrency(to);
        if (!(amount >= 0.0 && amount <= Double.MAX_VALUE)) {
            throw new IllegalArgumentException("Amount must be nonnegative and finite");
        }

        double fromRate;
        double toRate;
        long observedRevision;
        long observationTime;

        synchronized (lock) {
            observationTime = clock.now();
            int fromIndex = find(entries, from);
            int toIndex = find(entries, to);

            if (fromIndex < 0 || toIndex < 0
                    || entries[fromIndex].expiration <= observationTime
                    || entries[toIndex].expiration <= observationTime) {
                throw new IllegalStateException("Missing or expired rate");
            }

            fromRate = entries[fromIndex].rate;
            toRate = entries[toIndex].rate;
            observedRevision = revision;
        }

        double value = amount / fromRate * toRate;
        return new Conversion(value, observedRevision, observationTime);
    }

    public int prune() {
        synchronized (lock) {
            long now = clock.now();
            int removed = 0;
            for (int i = 0; i < entries.length; i++) {
                if (entries[i].expiration <= now) {
                    removed++;
                }
            }

            if (removed == 0) {
                return 0;
            }
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("Revision overflow");
            }

            Entry[] replacement = new Entry[entries.length - removed];
            int next = 0;
            for (int i = 0; i < entries.length; i++) {
                if (entries[i].expiration > now) {
                    replacement[next++] = entries[i];
                }
            }

            entries = replacement;
            revision++;
            return removed;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            long now = clock.now();
            Entry[] copy = new Entry[entries.length];
            for (int i = 0; i < entries.length; i++) {
                Entry entry = entries[i];
                copy[i] = new Entry(entry.currency, entry.rate, entry.expiration);
            }
            return new Snapshot(copy, revision, now);
        }
    }

    private static void validateCurrency(String currency) {
        if (currency == null || currency.length() == 0) {
            throw new IllegalArgumentException("Currency must not be null or empty");
        }
    }

    private static int find(Entry[] entries, String currency) {
        for (int i = 0; i < entries.length; i++) {
            if (entries[i].currency.equals(currency)) {
                return i;
            }
        }
        return -1;
    }
}

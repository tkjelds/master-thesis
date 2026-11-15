@TemporalForexCache.ThreadSafe
public final class TemporalForexCache {
    @interface ThreadSafe {}

    public interface Clock {
        long now();
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
            return entries.clone();
        }
    }

    private final Object monitor = new Object();
    private final Clock clock;

    // Accessed only while holding monitor. Entries themselves are immutable.
    private Entry[] entries = new Entry[0];
    private long revision;

    public TemporalForexCache(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
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
            throw new IllegalArgumentException("invalid batch arrays");
        }

        String[] namesCopy = currencies.clone();
        double[] ratesCopy = rates.clone();

        if (ttl <= 0) {
            throw new IllegalArgumentException("ttl must be positive");
        }

        for (int i = 0; i < namesCopy.length; i++) {
            validateCurrency(namesCopy[i]);
            validateRate(ratesCopy[i]);

            for (int j = 0; j < i; j++) {
                if (namesCopy[i].equals(namesCopy[j])) {
                    throw new IllegalArgumentException("duplicate currency");
                }
            }
        }

        synchronized (monitor) {
            long now = clock.now();
            if (now > Long.MAX_VALUE - ttl) {
                throw new IllegalArgumentException("expiration overflow");
            }
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision overflow");
            }

            long expiration = now + ttl;
            int missing = 0;
            for (int i = 0; i < namesCopy.length; i++) {
                if (indexOf(entries, namesCopy[i]) < 0) {
                    missing++;
                }
            }

            if (entries.length > Integer.MAX_VALUE - missing) {
                throw new IllegalStateException("too many currencies");
            }

            Entry[] replacement = new Entry[entries.length + missing];
            System.arraycopy(entries, 0, replacement, 0, entries.length);

            int next = entries.length;
            for (int i = 0; i < namesCopy.length; i++) {
                int index = indexOf(entries, namesCopy[i]);
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
        if (amount < 0 || Double.isNaN(amount) || Double.isInfinite(amount)) {
            throw new IllegalArgumentException("amount must be nonnegative and finite");
        }

        double fromRate;
        double toRate;
        long capturedRevision;
        long observationTime;

        synchronized (monitor) {
            observationTime = clock.now();
            int fromIndex = indexOf(entries, from);
            int toIndex = indexOf(entries, to);

            if (fromIndex < 0 || toIndex < 0
                    || entries[fromIndex].expiration <= observationTime
                    || entries[toIndex].expiration <= observationTime) {
                throw new IllegalStateException("missing or expired rate");
            }

            fromRate = entries[fromIndex].rate;
            toRate = entries[toIndex].rate;
            capturedRevision = revision;
        }

        return new Conversion(amount / fromRate * toRate,
                capturedRevision, observationTime);
    }

    public int prune() {
        synchronized (monitor) {
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
                throw new IllegalStateException("revision overflow");
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
        synchronized (monitor) {
            long observationTime = clock.now();
            Entry[] copy = new Entry[entries.length];

            for (int i = 0; i < entries.length; i++) {
                Entry entry = entries[i];
                copy[i] = new Entry(entry.currency, entry.rate, entry.expiration);
            }

            return new Snapshot(copy, revision, observationTime);
        }
    }

    private static int indexOf(Entry[] entries, String currency) {
        for (int i = 0; i < entries.length; i++) {
            if (entries[i].currency.equals(currency)) {
                return i;
            }
        }
        return -1;
    }

    private static void validateCurrency(String currency) {
        if (currency == null || currency.length() == 0) {
            throw new IllegalArgumentException("currency must not be null or empty");
        }
    }

    private static void validateRate(double rate) {
        if (!(rate > 0) || Double.isInfinite(rate)) {
            throw new IllegalArgumentException("rate must be positive and finite");
        }
    }
}

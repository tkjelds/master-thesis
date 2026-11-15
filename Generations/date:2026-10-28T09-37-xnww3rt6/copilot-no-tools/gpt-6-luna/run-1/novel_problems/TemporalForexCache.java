@TemporalForexCache.ThreadSafe
public final class TemporalForexCache {
    @interface ThreadSafe {}

    public interface Clock {
        long now();
    }

    public record Conversion(double value, long revision, long observationTime) {
    }

    public record Rate(String currency, double rate, long expiration) {
    }

    public static final class Snapshot {
        private final Rate[] entries;
        private final long revision;
        private final long observationTime;

        private Snapshot(Rate[] entries, long revision, long observationTime) {
            this.entries = new Rate[entries.length];
            for (int i = 0; i < entries.length; i++) {
                this.entries[i] = entries[i];
            }
            this.revision = revision;
            this.observationTime = observationTime;
        }

        public Rate[] entries() {
            Rate[] copy = new Rate[entries.length];
            for (int i = 0; i < entries.length; i++) {
                copy[i] = entries[i];
            }
            return copy;
        }

        public long revision() {
            return revision;
        }

        public long observationTime() {
            return observationTime;
        }
    }

    private static final class RateEntry {
        private final String currency;
        private final double rate;
        private final long expiration;

        private RateEntry(String currency, double rate, long expiration) {
            this.currency = currency;
            this.rate = rate;
            this.expiration = expiration;
        }
    }

    private static final class State {
        private final RateEntry[] entries;
        private final long revision;

        private State(RateEntry[] entries, long revision) {
            this.entries = entries;
            this.revision = revision;
        }
    }

    private final Clock clock;
    private final Object monitor = new Object();
    private State state = new State(new RateEntry[0], 0L);

    public TemporalForexCache(Clock clock) {
        if (clock == null) {
            throw new NullPointerException("clock");
        }
        this.clock = clock;
    }

    public void updateRate(String currency, double rate, long ttl) {
        updateRatesBatch(new String[] { currency }, new double[] { rate }, ttl);
    }

    public void updateRatesBatch(String[] currencies, double[] rates, long ttl) {
        if (currencies == null || rates == null) {
            throw new NullPointerException("batch arrays");
        }
        if (currencies.length == 0 || rates.length == 0
                || currencies.length != rates.length) {
            throw new IllegalArgumentException(
                    "batch arrays must have the same nonzero length");
        }

        String[] copiedCurrencies = new String[currencies.length];
        double[] copiedRates = new double[rates.length];
        for (int i = 0; i < currencies.length; i++) {
            copiedCurrencies[i] = currencies[i];
            copiedRates[i] = rates[i];
        }

        if (ttl <= 0L) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        for (int i = 0; i < copiedCurrencies.length; i++) {
            String currency = copiedCurrencies[i];
            if (currency == null || currency.length() == 0) {
                throw new IllegalArgumentException(
                        "currency names must be non-null and nonempty");
            }
            double rate = copiedRates[i];
            if (!(rate > 0.0) || !(rate <= Double.MAX_VALUE)) {
                throw new IllegalArgumentException(
                        "rates must be positive and finite");
            }
            for (int j = 0; j < i; j++) {
                if (currency.equals(copiedCurrencies[j])) {
                    throw new IllegalArgumentException(
                            "duplicate currency in batch");
                }
            }
        }

        synchronized (monitor) {
            State current = state;
            long observationTime = clock.now();
            if (observationTime > Long.MAX_VALUE - ttl) {
                throw new IllegalArgumentException("expiration time overflows");
            }
            if (current.revision == Long.MAX_VALUE) {
                throw new IllegalStateException("cache revision overflows");
            }

            int additions = 0;
            for (int i = 0; i < copiedCurrencies.length; i++) {
                if (findIndex(current.entries, copiedCurrencies[i]) < 0) {
                    additions++;
                }
            }
            if (additions > Integer.MAX_VALUE - current.entries.length) {
                throw new IllegalStateException("cache is too large");
            }

            int replacementCount = copiedCurrencies.length - additions;
            RateEntry[] nextEntries =
                    new RateEntry[current.entries.length - replacementCount
                            + copiedCurrencies.length];
            int nextIndex = 0;
            for (int i = 0; i < current.entries.length; i++) {
                RateEntry existing = current.entries[i];
                if (!contains(copiedCurrencies, existing.currency)) {
                    nextEntries[nextIndex++] = existing;
                }
            }

            long expiration = observationTime + ttl;
            for (int i = 0; i < copiedCurrencies.length; i++) {
                nextEntries[nextIndex++] =
                        new RateEntry(copiedCurrencies[i], copiedRates[i],
                                expiration);
            }
            state = new State(nextEntries, current.revision + 1L);
        }
    }

    public Conversion convert(String from, String to, double amount) {
        validateCurrencyName(from);
        validateCurrencyName(to);
        if (!(amount >= 0.0) || !(amount <= Double.MAX_VALUE)) {
            throw new IllegalArgumentException(
                    "amount must be nonnegative and finite");
        }

        double fromRate;
        double toRate;
        long capturedRevision;
        long observationTime;
        synchronized (monitor) {
            State captured = state;
            observationTime = clock.now();
            RateEntry fromEntry = findEntry(captured.entries, from);
            RateEntry toEntry = findEntry(captured.entries, to);
            if (fromEntry == null || fromEntry.expiration <= observationTime
                    || toEntry == null
                    || toEntry.expiration <= observationTime) {
                throw new IllegalStateException(
                        "a required currency rate is missing or expired");
            }
            fromRate = fromEntry.rate;
            toRate = toEntry.rate;
            capturedRevision = captured.revision;
        }

        double value = amount / fromRate * toRate;
        return new Conversion(value, capturedRevision, observationTime);
    }

    public int prune() {
        synchronized (monitor) {
            State current = state;
            long observationTime = clock.now();
            int removed = 0;
            for (int i = 0; i < current.entries.length; i++) {
                if (current.entries[i].expiration <= observationTime) {
                    removed++;
                }
            }
            if (removed == 0) {
                return 0;
            }
            if (current.revision == Long.MAX_VALUE) {
                throw new IllegalStateException("cache revision overflows");
            }

            RateEntry[] remaining =
                    new RateEntry[current.entries.length - removed];
            int nextIndex = 0;
            for (int i = 0; i < current.entries.length; i++) {
                RateEntry entry = current.entries[i];
                if (entry.expiration > observationTime) {
                    remaining[nextIndex++] = entry;
                }
            }
            state = new State(remaining, current.revision + 1L);
            return removed;
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            State captured = state;
            long observationTime = clock.now();
            Rate[] copiedEntries = new Rate[captured.entries.length];
            for (int i = 0; i < captured.entries.length; i++) {
                RateEntry entry = captured.entries[i];
                copiedEntries[i] =
                        new Rate(entry.currency, entry.rate, entry.expiration);
            }
            return new Snapshot(
                    copiedEntries, captured.revision, observationTime);
        }
    }

    private static void validateCurrencyName(String currency) {
        if (currency == null || currency.length() == 0) {
            throw new IllegalArgumentException(
                    "currency names must be non-null and nonempty");
        }
    }

    private static int findIndex(RateEntry[] entries, String currency) {
        for (int i = 0; i < entries.length; i++) {
            if (entries[i].currency.equals(currency)) {
                return i;
            }
        }
        return -1;
    }

    private static RateEntry findEntry(RateEntry[] entries, String currency) {
        int index = findIndex(entries, currency);
        return index < 0 ? null : entries[index];
    }

    private static boolean contains(String[] currencies, String currency) {
        for (int i = 0; i < currencies.length; i++) {
            if (currencies[i].equals(currency)) {
                return true;
            }
        }
        return false;
    }
}

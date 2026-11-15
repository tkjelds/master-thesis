@AdaptiveTrustVault.ThreadSafe
public final class AdaptiveTrustVault {
    @interface ThreadSafe {}

    public interface Clock {
        long now();
    }

    public static final class Metrics {
        public final long truePositives;
        public final long falsePositives;
        public final long falseNegatives;
        public final long trueNegatives;
        public final double f1;
        public final double effectiveTTL;
        public final int storedEntryCount;

        private Metrics(long truePositives, long falsePositives,
                        long falseNegatives, long trueNegatives,
                        double f1, double effectiveTTL, int storedEntryCount) {
            this.truePositives = truePositives;
            this.falsePositives = falsePositives;
            this.falseNegatives = falseNegatives;
            this.trueNegatives = trueNegatives;
            this.f1 = f1;
            this.effectiveTTL = effectiveTTL;
            this.storedEntryCount = storedEntryCount;
        }

        public long getTruePositives() {
            return truePositives;
        }

        public long getFalsePositives() {
            return falsePositives;
        }

        public long getFalseNegatives() {
            return falseNegatives;
        }

        public long getTrueNegatives() {
            return trueNegatives;
        }

        public double getF1() {
            return f1;
        }

        public double getEffectiveTTL() {
            return effectiveTTL;
        }

        public int getStoredEntryCount() {
            return storedEntryCount;
        }
    }

    private static final class Entry {
        final String key;
        final String credential;
        final byte[] payload;
        final long depositedAt;

        Entry(String key, String credential, byte[] payload, long depositedAt) {
            this.key = key;
            this.credential = credential;
            this.payload = payload;
            this.depositedAt = depositedAt;
        }
    }

    private final Object mutex = new Object();
    private final long baseTTL;
    private final Clock clock;
    private Entry[] entries = new Entry[8];
    private int entryCount;

    private long tp;
    private long fp;
    private long fn;
    private long tn;

    private boolean hasObservedTime;
    private long lastObservedTime;

    public AdaptiveTrustVault(long baseTTL, Clock clock) {
        if (baseTTL <= 0) {
            throw new IllegalArgumentException("baseTTL must be positive");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.baseTTL = baseTTL;
        this.clock = clock;
    }

    public void deposit(String key, String credential, byte[] payload) {
        if (key == null || credential == null || payload == null) {
            throw new IllegalArgumentException("inputs must not be null");
        }
        if (credential.length() == 0) {
            throw new IllegalArgumentException("credential must not be empty");
        }

        byte[] retainedPayload = payload.clone();

        synchronized (mutex) {
            long now = readTimeLocked();
            int index = findEntryLocked(key);
            if (index >= 0) {
                entries[index] = new Entry(key, credential, retainedPayload, now);
                return;
            }

            ensureCapacityLocked();
            entries[entryCount++] = new Entry(key, credential, retainedPayload, now);
        }
    }

    public byte[] attemptRetrieve(String key, String credential) {
        if (key == null || credential == null) {
            throw new IllegalArgumentException("inputs must not be null");
        }

        synchronized (mutex) {
            long now = readTimeLocked();
            double preAttemptTTL = effectiveTTLLocked();
            int index = findEntryLocked(key);
            Entry candidate = index < 0 ? null : entries[index];
            boolean initiallyAlive = candidate != null
                    && isAlive(candidate, now, preAttemptTTL);
            boolean matched = initiallyAlive
                    && candidate.credential.equals(credential);

            int classification;
            if (initiallyAlive) {
                classification = matched ? 0 : 2;
            } else {
                classification = credential.length() == 0 ? 3 : 1;
            }

            ensureCounterAvailableLocked(classification);

            if (candidate != null && !initiallyAlive) {
                removeAtLocked(index);
                candidate = null;
            }

            incrementCounterLocked(classification);
            double updatedTTL = effectiveTTLLocked();

            if (!matched || candidate == null) {
                return null;
            }

            if (!isAlive(candidate, now, updatedTTL)) {
                removeAtLocked(index);
                return null;
            }

            return candidate.payload.clone();
        }
    }

    public void cleanup() {
        synchronized (mutex) {
            long now = readTimeLocked();
            double ttl = effectiveTTLLocked();
            int index = 0;
            while (index < entryCount) {
                if (!isAlive(entries[index], now, ttl)) {
                    removeAtLocked(index);
                } else {
                    index++;
                }
            }
        }
    }

    public Metrics snapshotMetrics() {
        synchronized (mutex) {
            double f1 = f1Locked();
            return new Metrics(tp, fp, fn, tn, f1, baseTTL * f1, entryCount);
        }
    }

    private long readTimeLocked() {
        long now = clock.now();
        if (now < 0 || (hasObservedTime && now < lastObservedTime)) {
            throw new IllegalStateException("clock must be nonnegative and monotonic");
        }
        hasObservedTime = true;
        lastObservedTime = now;
        return now;
    }

    private int findEntryLocked(String key) {
        for (int i = 0; i < entryCount; i++) {
            if (entries[i].key.equals(key)) {
                return i;
            }
        }
        return -1;
    }

    private void ensureCapacityLocked() {
        if (entryCount < entries.length) {
            return;
        }
        if (entryCount == Integer.MAX_VALUE || entries.length > Integer.MAX_VALUE / 2) {
            throw new IllegalStateException("vault capacity exhausted");
        }

        int newLength = entries.length * 2;
        Entry[] expanded = new Entry[newLength];
        for (int i = 0; i < entryCount; i++) {
            expanded[i] = entries[i];
        }
        entries = expanded;
    }

    private void removeAtLocked(int index) {
        int remaining = entryCount - index - 1;
        for (int i = 0; i < remaining; i++) {
            entries[index + i] = entries[index + i + 1];
        }
        entries[--entryCount] = null;
    }

    private boolean isAlive(Entry entry, long now, double ttl) {
        return (double) (now - entry.depositedAt) < ttl;
    }

    private double f1Locked() {
        double numerator = 2.0 * (double) tp;
        double denominator = numerator + (double) fp + (double) fn;
        return denominator == 0.0 ? 1.0 : numerator / denominator;
    }

    private double effectiveTTLLocked() {
        return baseTTL * f1Locked();
    }

    private void ensureCounterAvailableLocked(int classification) {
        long counter;
        switch (classification) {
            case 0:
                counter = tp;
                break;
            case 1:
                counter = fp;
                break;
            case 2:
                counter = fn;
                break;
            default:
                counter = tn;
                break;
        }
        if (counter == Long.MAX_VALUE) {
            throw new IllegalStateException("metric counter exhausted");
        }
    }

    private void incrementCounterLocked(int classification) {
        switch (classification) {
            case 0:
                tp++;
                break;
            case 1:
                fp++;
                break;
            case 2:
                fn++;
                break;
            default:
                tn++;
                break;
        }
    }
}

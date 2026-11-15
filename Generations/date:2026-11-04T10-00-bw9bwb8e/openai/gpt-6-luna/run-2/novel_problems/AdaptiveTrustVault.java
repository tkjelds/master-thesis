@AdaptiveTrustVault.ThreadSafe
public class AdaptiveTrustVault {
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
        public final double effectiveTtl;
        public final int storedEntryCount;

        private Metrics(long truePositives, long falsePositives,
                        long falseNegatives, long trueNegatives,
                        double f1, double effectiveTtl, int storedEntryCount) {
            this.truePositives = truePositives;
            this.falsePositives = falsePositives;
            this.falseNegatives = falseNegatives;
            this.trueNegatives = trueNegatives;
            this.f1 = f1;
            this.effectiveTtl = effectiveTtl;
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

        public double getEffectiveTtl() {
            return effectiveTtl;
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

    private final Object lock = new Object();
    private final long baseTtl;
    private final Clock clock;

    private Entry[] entries = new Entry[8];
    private int size;

    private long truePositives;
    private long falsePositives;
    private long falseNegatives;
    private long trueNegatives;

    public AdaptiveTrustVault(long baseTtl, Clock clock) {
        if (baseTtl <= 0) {
            throw new IllegalArgumentException("baseTtl must be positive");
        }
        if (clock == null) {
            throw new NullPointerException("clock");
        }
        this.baseTtl = baseTtl;
        this.clock = clock;
    }

    public void deposit(String key, String credential, byte[] payload) {
        if (key == null) {
            throw new NullPointerException("key");
        }
        if (credential == null) {
            throw new NullPointerException("credential");
        }
        if (payload == null) {
            throw new NullPointerException("payload");
        }
        if (credential.length() == 0) {
            throw new IllegalArgumentException("Deposited credentials must not be empty");
        }

        byte[] payloadCopy = copyBytes(payload);

        synchronized (lock) {
            long now = readTime();
            int index = findIndexLocked(key);
            Entry replacement = new Entry(key, credential, payloadCopy, now);

            if (index >= 0) {
                entries[index] = replacement;
            } else {
                ensureCapacityLocked();
                entries[size++] = replacement;
            }
        }
    }

    public byte[] attemptRetrieve(String key, String credential) {
        if (key == null) {
            throw new NullPointerException("key");
        }
        if (credential == null) {
            throw new NullPointerException("credential");
        }

        synchronized (lock) {
            long now = readTime();
            double preAttemptTtl = effectiveTtlLocked();

            int index = findIndexLocked(key);
            Entry entry = index >= 0 ? entries[index] : null;
            boolean aliveBeforeAttempt = entry != null && isAlive(entry, now, preAttemptTtl);

            int classification;
            if (aliveBeforeAttempt) {
                classification = entry.credential.equals(credential) ? 0 : 2;
            } else {
                classification = credential.length() == 0 ? 3 : 1;
            }

            checkCounterAvailableLocked(classification);

            switch (classification) {
                case 0:
                    truePositives++;
                    break;
                case 1:
                    falsePositives++;
                    break;
                case 2:
                    falseNegatives++;
                    break;
                default:
                    trueNegatives++;
                    break;
            }

            if (entry != null && !aliveBeforeAttempt) {
                removeAtLocked(index);
            }

            double postAttemptTtl = effectiveTtlLocked();
            if (!aliveBeforeAttempt || classification != 0) {
                return null;
            }

            if (!isAlive(entry, now, postAttemptTtl)) {
                int currentIndex = findEntryIdentityLocked(entry);
                if (currentIndex >= 0) {
                    removeAtLocked(currentIndex);
                }
                return null;
            }

            return copyBytes(entry.payload);
        }
    }

    public void cleanup() {
        synchronized (lock) {
            long now = readTime();
            double ttl = effectiveTtlLocked();

            int writeIndex = 0;
            for (int readIndex = 0; readIndex < size; readIndex++) {
                Entry entry = entries[readIndex];
                if (isAlive(entry, now, ttl)) {
                    entries[writeIndex++] = entry;
                }
            }

            for (int i = writeIndex; i < size; i++) {
                entries[i] = null;
            }
            size = writeIndex;
        }
    }

    public Metrics snapshotMetrics() {
        synchronized (lock) {
            double f1 = f1Locked();
            return new Metrics(
                    truePositives,
                    falsePositives,
                    falseNegatives,
                    trueNegatives,
                    f1,
                    baseTtl * f1,
                    size);
        }
    }

    private long readTime() {
        long now = clock.now();
        if (now < 0) {
            throw new IllegalStateException("Clock returned a negative time");
        }
        return now;
    }

    private boolean isAlive(Entry entry, long now, double ttl) {
        if (now < entry.depositedAt) {
            throw new IllegalStateException("Clock is not monotonic");
        }
        long age = now - entry.depositedAt;
        return age < ttl;
    }

    private double f1Locked() {
        double tp = (double) truePositives;
        double twiceTp = 2.0 * tp;
        double denominator = twiceTp
                + (double) falsePositives
                + (double) falseNegatives;
        if (denominator == 0.0) {
            return 1.0;
        }
        return twiceTp / denominator;
    }

    private double effectiveTtlLocked() {
        return baseTtl * f1Locked();
    }

    private int findIndexLocked(String key) {
        for (int i = 0; i < size; i++) {
            if (entries[i].key.equals(key)) {
                return i;
            }
        }
        return -1;
    }

    private int findEntryIdentityLocked(Entry target) {
        for (int i = 0; i < size; i++) {
            if (entries[i] == target) {
                return i;
            }
        }
        return -1;
    }

    private void removeAtLocked(int index) {
        for (int i = index; i < size - 1; i++) {
            entries[i] = entries[i + 1];
        }
        entries[--size] = null;
    }

    private void ensureCapacityLocked() {
        if (size < entries.length) {
            return;
        }

        int oldCapacity = entries.length;
        int newCapacity;
        if (oldCapacity == 0) {
            newCapacity = 8;
        } else if (oldCapacity > Integer.MAX_VALUE / 2) {
            newCapacity = Integer.MAX_VALUE;
        } else {
            newCapacity = oldCapacity * 2;
        }

        if (newCapacity <= size) {
            throw new IllegalStateException("Vault capacity exhausted");
        }

        Entry[] expanded = new Entry[newCapacity];
        for (int i = 0; i < size; i++) {
            expanded[i] = entries[i];
        }
        entries = expanded;
    }

    private void checkCounterAvailableLocked(int classification) {
        long counter;
        switch (classification) {
            case 0:
                counter = truePositives;
                break;
            case 1:
                counter = falsePositives;
                break;
            case 2:
                counter = falseNegatives;
                break;
            default:
                counter = trueNegatives;
                break;
        }

        if (counter == Long.MAX_VALUE) {
            throw new IllegalStateException("Metric counter exhausted");
        }
    }

    private static byte[] copyBytes(byte[] source) {
        byte[] copy = new byte[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }
}

@AdaptiveTrustVault.ThreadSafe
public final class AdaptiveTrustVault {
    @interface ThreadSafe {}

    public interface Clock {
        long now();
    }

    public static final class Metrics {
        public final long tp;
        public final long fp;
        public final long fn;
        public final long tn;
        public final double f1;
        public final double effectiveTTL;
        public final int storedEntries;

        private Metrics(long tp, long fp, long fn, long tn, double f1,
                        double effectiveTTL, int storedEntries) {
            this.tp = tp;
            this.fp = fp;
            this.fn = fn;
            this.tn = tn;
            this.f1 = f1;
            this.effectiveTTL = effectiveTTL;
            this.storedEntries = storedEntries;
        }
    }

    private static final class Entry {
        final String credential;
        final byte[] payload;
        final long depositTime;

        Entry(String credential, byte[] payload, long depositTime) {
            this.credential = credential;
            this.payload = payload;
            this.depositTime = depositTime;
        }
    }

    private final Object lock = new Object();
    private final long baseTTL;
    private final Clock clock;

    private Entry[] entries = new Entry[8];
    private int size;

    private long tp;
    private long fp;
    private long fn;
    private long tn;

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
            throw new IllegalArgumentException("arguments must not be null");
        }
        if (credential.length() == 0) {
            throw new IllegalArgumentException("credential must not be empty");
        }

        byte[] payloadCopy = copyBytes(payload);

        synchronized (lock) {
            long now = readClock();
            int index = findIndex(key);
            Entry replacement = new Entry(credential, payloadCopy, now);

            if (index >= 0) {
                entries[index] = replacement;
            } else {
                ensureCapacity();
                entries[size++] = replacement;
            }
        }
    }

    public byte[] attemptRetrieve(String key, String credential) {
        if (key == null || credential == null) {
            throw new IllegalArgumentException("arguments must not be null");
        }

        synchronized (lock) {
            long now = readClock();
            double preAttemptTTL = effectiveTTL();

            int index = findIndex(key);
            Entry candidate = index < 0 ? null : entries[index];
            boolean aliveBefore = candidate != null
                    && isAlive(candidate, now, preAttemptTTL);

            boolean matched = aliveBefore
                    && candidate.credential.equals(credential);

            if (aliveBefore) {
                if (matched) {
                    ensureCounterCanIncrement(tp, "TP");
                } else {
                    ensureCounterCanIncrement(fn, "FN");
                }
            } else if (credential.length() != 0) {
                ensureCounterCanIncrement(fp, "FP");
            } else {
                ensureCounterCanIncrement(tn, "TN");
            }

            if (aliveBefore) {
                if (matched) {
                    tp++;
                } else {
                    fn++;
                }
            } else if (credential.length() != 0) {
                fp++;
            } else {
                tn++;
            }

            if (candidate != null && !aliveBefore) {
                removeAt(index);
                candidate = null;
            }

            double postAttemptTTL = effectiveTTL();
            boolean aliveAfter = candidate != null
                    && isAlive(candidate, now, postAttemptTTL);

            if (candidate != null && !aliveAfter) {
                int currentIndex = findEntry(candidate);
                if (currentIndex >= 0) {
                    removeAt(currentIndex);
                }
            }

            if (matched && aliveAfter) {
                return copyBytes(candidate.payload);
            }
            return null;
        }
    }

    public void cleanup() {
        synchronized (lock) {
            long now = readClock();
            double ttl = effectiveTTL();

            int index = 0;
            while (index < size) {
                if (!isAlive(entries[index], now, ttl)) {
                    removeAt(index);
                } else {
                    index++;
                }
            }
        }
    }

    public Metrics snapshotMetrics() {
        synchronized (lock) {
            double f1 = calculateF1();
            return new Metrics(tp, fp, fn, tn, f1, baseTTL * f1, size);
        }
    }

    private long readClock() {
        long now = clock.now();
        if (now < 0) {
            throw new IllegalStateException("clock returned a negative time");
        }
        return now;
    }

    private double effectiveTTL() {
        return baseTTL * calculateF1();
    }

    private double calculateF1() {
        double twiceTP = 2.0 * (double) tp;
        double denominator = twiceTP + (double) fp + (double) fn;
        if (denominator == 0.0) {
            return 1.0;
        }
        return twiceTP / denominator;
    }

    private static boolean isAlive(Entry entry, long now, double ttl) {
        long age = now - entry.depositTime;
        return (double) age < ttl;
    }

    private int findIndex(String key) {
        for (int i = 0; i < size; i++) {
            // Keys are stored separately in the entry table below via the key field.
            if (entries[i] != null && keyAt(i).equals(key)) {
                return i;
            }
        }
        return -1;
    }

    private String keyAt(int index) {
        return keys[index];
    }

    private int findEntry(Entry entry) {
        for (int i = 0; i < size; i++) {
            if (entries[i] == entry) {
                return i;
            }
        }
        return -1;
    }

    private void ensureCapacity() {
        if (size < entries.length) {
            return;
        }
        if (entries.length == Integer.MAX_VALUE) {
            throw new IllegalStateException("vault capacity exhausted");
        }

        int newCapacity;
        if (entries.length == 0) {
            newCapacity = 8;
        } else if (entries.length > Integer.MAX_VALUE / 2) {
            newCapacity = Integer.MAX_VALUE;
        } else {
            newCapacity = entries.length * 2;
        }
        if (newCapacity <= size) {
            throw new IllegalStateException("vault capacity exhausted");
        }

        Entry[] newEntries = new Entry[newCapacity];
        String[] newKeys = new String[newCapacity];
        for (int i = 0; i < size; i++) {
            newEntries[i] = entries[i];
            newKeys[i] = keys[i];
        }
        entries = newEntries;
        keys = newKeys;
    }

    private void removeAt(int index) {
        int last = size - 1;
        for (int i = index; i < last; i++) {
            entries[i] = entries[i + 1];
            keys[i] = keys[i + 1];
        }
        entries[last] = null;
        keys[last] = null;
        size--;
    }

    private static void ensureCounterCanIncrement(long value, String name) {
        if (value == Long.MAX_VALUE) {
            throw new IllegalStateException(name + " counter exhausted");
        }
    }

    private static byte[] copyBytes(byte[] source) {
        byte[] copy = new byte[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }

    private String[] keys = new String[8];
}

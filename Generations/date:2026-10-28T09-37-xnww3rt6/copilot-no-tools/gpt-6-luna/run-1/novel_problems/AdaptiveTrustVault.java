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
        public final double effectiveTtl;
        public final long storedEntryCount;

        private Metrics(long truePositives, long falsePositives,
                       long falseNegatives, long trueNegatives,
                       double f1, double effectiveTtl, long storedEntryCount) {
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

        public long getStoredEntryCount() {
            return storedEntryCount;
        }
    }

    private static final class Entry {
        private final String key;
        private String credential;
        private byte[] payload;
        private long depositTime;
        private Entry next;

        private Entry(String key, String credential, byte[] payload,
                      long depositTime, Entry next) {
            this.key = key;
            this.credential = credential;
            this.payload = payload;
            this.depositTime = depositTime;
            this.next = next;
        }
    }

    private final Object monitor = new Object();
    private final long baseTtl;
    private final Clock clock;

    private Entry head;
    private long storedEntryCount;
    private long truePositives;
    private long falsePositives;
    private long falseNegatives;
    private long trueNegatives;
    private double f1 = 1.0;
    private double effectiveTtl;

    public AdaptiveTrustVault(long baseTtl, Clock clock) {
        if (baseTtl <= 0) {
            throw new IllegalArgumentException("baseTtl must be positive");
        }
        if (clock == null) {
            throw new NullPointerException("clock");
        }
        this.baseTtl = baseTtl;
        this.clock = clock;
        this.effectiveTtl = baseTtl;
    }

    public void deposit(String key, String credential, byte[] payload) {
        requireNonNull(key, "key");
        requireNonNull(credential, "credential");
        requireNonNull(payload, "payload");
        if (credential.length() == 0) {
            throw new IllegalArgumentException("credential must not be empty");
        }

        byte[] retainedPayload = payload.clone();

        synchronized (monitor) {
            Entry entry = findEntry(key);
            if (entry == null && storedEntryCount == Long.MAX_VALUE) {
                throw new ArithmeticException("stored-entry count exhausted");
            }

            long depositTime = clock.now();
            if (entry == null) {
                head = new Entry(key, credential, retainedPayload, depositTime, head);
                storedEntryCount++;
            } else {
                entry.credential = credential;
                entry.payload = retainedPayload;
                entry.depositTime = depositTime;
            }
        }
    }

    public byte[] attemptRetrieve(String key, String credential) {
        requireNonNull(key, "key");
        requireNonNull(credential, "credential");

        synchronized (monitor) {
            long now = clock.now();
            Entry entry = findEntry(key);
            boolean aliveBeforeAttempt =
                    entry != null && isAlive(entry, now, effectiveTtl);

            long counterToIncrement;
            if (aliveBeforeAttempt) {
                counterToIncrement = credential.equals(entry.credential)
                        ? truePositives : falseNegatives;
            } else {
                counterToIncrement = credential.length() == 0
                        ? trueNegatives : falsePositives;
            }
            requireCounterAvailable(counterToIncrement);

            if (entry != null && !aliveBeforeAttempt) {
                removeEntry(entry);
                entry = null;
            }

            if (aliveBeforeAttempt) {
                if (credential.equals(entry.credential)) {
                    truePositives++;
                } else {
                    falseNegatives++;
                }
            } else if (credential.length() == 0) {
                trueNegatives++;
            } else {
                falsePositives++;
            }

            recomputeTtl();

            if (!aliveBeforeAttempt || entry == null) {
                return null;
            }

            boolean aliveAfterAttempt = isAlive(entry, now, effectiveTtl);
            if (!aliveAfterAttempt) {
                removeEntry(entry);
                return null;
            }

            if (!credential.equals(entry.credential)) {
                return null;
            }
            return entry.payload.clone();
        }
    }

    public void cleanup() {
        synchronized (monitor) {
            long now = clock.now();
            double ttl = effectiveTtl;

            Entry previous = null;
            Entry current = head;
            while (current != null) {
                Entry next = current.next;
                if (!isAlive(current, now, ttl)) {
                    if (previous == null) {
                        head = next;
                    } else {
                        previous.next = next;
                    }
                    storedEntryCount--;
                } else {
                    previous = current;
                }
                current = next;
            }
        }
    }

    public Metrics snapshotMetrics() {
        synchronized (monitor) {
            return new Metrics(truePositives, falsePositives, falseNegatives,
                    trueNegatives, f1, effectiveTtl, storedEntryCount);
        }
    }

    private Entry findEntry(String key) {
        Entry current = head;
        while (current != null) {
            if (current.key.equals(key)) {
                return current;
            }
            current = current.next;
        }
        return null;
    }

    private void removeEntry(Entry target) {
        Entry previous = null;
        Entry current = head;
        while (current != null) {
            if (current == target) {
                if (previous == null) {
                    head = current.next;
                } else {
                    previous.next = current.next;
                }
                storedEntryCount--;
                return;
            }
            previous = current;
            current = current.next;
        }
    }

    private boolean isAlive(Entry entry, long now, double ttl) {
        long age = now - entry.depositTime;
        return age < ttl;
    }

    private void recomputeTtl() {
        double twiceTruePositives = 2.0 * (double) truePositives;
        double denominator = twiceTruePositives
                + (double) falsePositives + (double) falseNegatives;
        f1 = denominator == 0.0 ? 1.0 : twiceTruePositives / denominator;
        effectiveTtl = (double) baseTtl * f1;
    }

    private static void requireCounterAvailable(long counter) {
        if (counter == Long.MAX_VALUE) {
            throw new ArithmeticException("metric counter exhausted");
        }
    }

    private static void requireNonNull(Object value, String name) {
        if (value == null) {
            throw new NullPointerException(name);
        }
    }
}

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
        public final long storedEntries;

        private Metrics(long truePositives, long falsePositives,
                       long falseNegatives, long trueNegatives,
                       double f1, double effectiveTtl, long storedEntries) {
            this.truePositives = truePositives;
            this.falsePositives = falsePositives;
            this.falseNegatives = falseNegatives;
            this.trueNegatives = trueNegatives;
            this.f1 = f1;
            this.effectiveTtl = effectiveTtl;
            this.storedEntries = storedEntries;
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

        public long getStoredEntries() {
            return storedEntries;
        }
    }

    private static final int TP = 0;
    private static final int FP = 1;
    private static final int FN = 2;
    private static final int TN = 3;

    private static final class Entry {
        private final String key;
        private final String credential;
        private final byte[] payload;
        private final long depositTime;
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
    private long storedEntries;

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
        if (credential.length() == 0) {
            throw new IllegalArgumentException("credential must not be empty");
        }
        if (payload == null) {
            throw new NullPointerException("payload");
        }

        byte[] payloadCopy = payload.clone();

        synchronized (monitor) {
            long depositTime = clock.now();
            Entry previous = null;
            Entry current = head;

            while (current != null) {
                if (current.key.equals(key)) {
                    Entry replacement =
                            new Entry(key, credential, payloadCopy, depositTime, current.next);
                    if (previous == null) {
                        head = replacement;
                    } else {
                        previous.next = replacement;
                    }
                    return;
                }
                previous = current;
                current = current.next;
            }

            if (storedEntries == Long.MAX_VALUE) {
                throw new ArithmeticException("stored-entry count exhausted");
            }
            head = new Entry(key, credential, payloadCopy, depositTime, head);
            storedEntries++;
        }
    }

    public byte[] attemptRetrieve(String key, String credential) {
        if (key == null) {
            throw new NullPointerException("key");
        }
        if (credential == null) {
            throw new NullPointerException("credential");
        }

        synchronized (monitor) {
            long now = clock.now();
            double preAttemptTtl = effectiveTtlLocked();
            Entry entry = findLocked(key);
            long age = entry == null ? 0 : now - entry.depositTime;
            boolean alive = entry != null && isAlive(age, preAttemptTtl);
            boolean credentialMatches = alive && entry.credential.equals(credential);

            int classification;
            if (alive) {
                classification = credentialMatches ? TP : FN;
            } else {
                classification = credential.length() == 0 ? TN : FP;
            }

            ensureCounterAvailableLocked(classification);

            if (entry != null && !alive) {
                removeLocked(entry);
            }
            incrementCounterLocked(classification);

            double newTtl = effectiveTtlLocked();
            boolean stillAlive = alive && isAlive(age, newTtl);
            if (!stillAlive && alive) {
                removeLocked(entry);
            }

            if (credentialMatches && stillAlive) {
                return entry.payload.clone();
            }
            return null;
        }
    }

    public void cleanup() {
        synchronized (monitor) {
            long now = clock.now();
            double ttl = effectiveTtlLocked();

            Entry previous = null;
            Entry current = head;
            while (current != null) {
                Entry next = current.next;
                long age = now - current.depositTime;
                if (!isAlive(age, ttl)) {
                    if (previous == null) {
                        head = next;
                    } else {
                        previous.next = next;
                    }
                    storedEntries--;
                } else {
                    previous = current;
                }
                current = next;
            }
        }
    }

    public Metrics snapshotMetrics() {
        synchronized (monitor) {
            double f1 = f1Locked();
            return new Metrics(
                    truePositives,
                    falsePositives,
                    falseNegatives,
                    trueNegatives,
                    f1,
                    ((double) baseTtl) * f1,
                    storedEntries);
        }
    }

    private Entry findLocked(String key) {
        Entry current = head;
        while (current != null) {
            if (current.key.equals(key)) {
                return current;
            }
            current = current.next;
        }
        return null;
    }

    private void removeLocked(Entry target) {
        Entry previous = null;
        Entry current = head;
        while (current != null) {
            if (current == target) {
                if (previous == null) {
                    head = current.next;
                } else {
                    previous.next = current.next;
                }
                storedEntries--;
                return;
            }
            previous = current;
            current = current.next;
        }
    }

    private boolean isAlive(long age, double ttl) {
        return ((double) age) < ttl;
    }

    private double f1Locked() {
        double twiceTruePositives = ((double) truePositives) * 2.0;
        double denominator = twiceTruePositives
                + (double) falsePositives
                + (double) falseNegatives;
        return denominator == 0.0 ? 1.0 : twiceTruePositives / denominator;
    }

    private double effectiveTtlLocked() {
        return ((double) baseTtl) * f1Locked();
    }

    private void ensureCounterAvailableLocked(int classification) {
        long value;
        if (classification == TP) {
            value = truePositives;
        } else if (classification == FP) {
            value = falsePositives;
        } else if (classification == FN) {
            value = falseNegatives;
        } else {
            value = trueNegatives;
        }

        if (value == Long.MAX_VALUE) {
            throw new ArithmeticException("metric counter exhausted");
        }
    }

    private void incrementCounterLocked(int classification) {
        if (classification == TP) {
            truePositives++;
        } else if (classification == FP) {
            falsePositives++;
        } else if (classification == FN) {
            falseNegatives++;
        } else {
            trueNegatives++;
        }
    }
}

@AdaptiveTrustVault.ThreadSafe
public final class AdaptiveTrustVault {
    @interface ThreadSafe {}

    public interface Clock {
        long now();
    }

    public static final class Metrics {
        private final long truePositives;
        private final long falsePositives;
        private final long falseNegatives;
        private final long trueNegatives;
        private final double f1;
        private final double effectiveTtl;
        private final long storedEntryCount;

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
        private final String credential;
        private final byte[] payload;
        private final long depositedAt;
        private Entry next;

        private Entry(String key, String credential, byte[] payload,
                      long depositedAt, Entry next) {
            this.key = key;
            this.credential = credential;
            this.payload = payload;
            this.depositedAt = depositedAt;
            this.next = next;
        }
    }

    private final Object lock = new Object();
    private final long baseTtl;
    private final Clock clock;
    private Entry head;
    private long storedEntryCount;
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
        requireNonNull(key, "key");
        requireNonNull(credential, "credential");
        requireNonNull(payload, "payload");
        if (credential.length() == 0) {
            throw new IllegalArgumentException("credential must not be empty");
        }

        byte[] retainedPayload = copy(payload);
        synchronized (lock) {
            long depositedAt = clock.now();
            Entry previous = null;
            Entry current = head;
            while (current != null) {
                if (current.key.equals(key)) {
                    Entry replacement =
                            new Entry(key, credential, retainedPayload,
                                      depositedAt, current.next);
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

            if (storedEntryCount == Long.MAX_VALUE) {
                throw new ArithmeticException("stored-entry count exhausted");
            }
            head = new Entry(key, credential, retainedPayload, depositedAt, head);
            storedEntryCount++;
        }
    }

    public byte[] attemptRetrieve(String key, String credential) {
        requireNonNull(key, "key");
        requireNonNull(credential, "credential");

        synchronized (lock) {
            long now = clock.now();
            Entry entry = find(key);
            double ttlBeforeAttempt = effectiveTtl();
            boolean aliveBeforeAttempt =
                    entry != null && isAlive(entry, now, ttlBeforeAttempt);
            boolean credentialsMatch =
                    aliveBeforeAttempt && entry.credential.equals(credential);

            if (aliveBeforeAttempt) {
                if (credentialsMatch) {
                    ensureCanIncrement(truePositives);
                    truePositives++;
                } else {
                    ensureCanIncrement(falseNegatives);
                    falseNegatives++;
                }
            } else if (credential.length() != 0) {
                ensureCanIncrement(falsePositives);
                falsePositives++;
            } else {
                ensureCanIncrement(trueNegatives);
                trueNegatives++;
            }

            double ttlAfterAttempt = effectiveTtl();
            boolean aliveAfterAttempt =
                    aliveBeforeAttempt && isAlive(entry, now, ttlAfterAttempt);
            if (!aliveAfterAttempt && entry != null) {
                removeEntry(entry);
            }

            if (credentialsMatch && aliveAfterAttempt) {
                return copy(entry.payload);
            }
            return null;
        }
    }

    public void cleanup() {
        synchronized (lock) {
            long now = clock.now();
            double ttl = effectiveTtl();
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
                    current.next = null;
                    storedEntryCount--;
                } else {
                    previous = current;
                }
                current = next;
            }
        }
    }

    public Metrics snapshotMetrics() {
        synchronized (lock) {
            double f1 = f1();
            return new Metrics(truePositives, falsePositives, falseNegatives,
                               trueNegatives, f1, baseTtl * f1,
                               storedEntryCount);
        }
    }

    private Entry find(String key) {
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
                current.next = null;
                storedEntryCount--;
                return;
            }
            previous = current;
            current = current.next;
        }
    }

    private boolean isAlive(Entry entry, long now, double ttl) {
        return ((double) (now - entry.depositedAt)) < ttl;
    }

    private double effectiveTtl() {
        return baseTtl * f1();
    }

    private double f1() {
        double twiceTruePositives = 2.0 * (double) truePositives;
        double denominator = twiceTruePositives
                + (double) falsePositives + (double) falseNegatives;
        if (denominator == 0.0) {
            return 1.0;
        }
        return twiceTruePositives / denominator;
    }

    private static void ensureCanIncrement(long value) {
        if (value == Long.MAX_VALUE) {
            throw new ArithmeticException("metric counter exhausted");
        }
    }

    private static void requireNonNull(Object value, String name) {
        if (value == null) {
            throw new NullPointerException(name);
        }
    }

    private static byte[] copy(byte[] source) {
        byte[] result = new byte[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i];
        }
        return result;
    }
}

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
        byte[] payloadCopy = payload.clone();

        synchronized (lock) {
            long now = captureTime();
            Entry previous = null;
            Entry current = head;
            while (current != null) {
                if (current.key.equals(key)) {
                    Entry replacement =
                            new Entry(key, credential, payloadCopy, now, current.next);
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
                throw new ArithmeticException("stored entry count exhausted");
            }
            head = new Entry(key, credential, payloadCopy, now, head);
            storedEntryCount++;
        }
    }

    public byte[] attemptRetrieve(String key, String credential) {
        requireNonNull(key, "key");
        requireNonNull(credential, "credential");

        synchronized (lock) {
            long now = captureTime();
            double ttlBeforeAttempt = effectiveTtlLocked();
            Entry entry = findEntry(key);
            boolean aliveBeforeAttempt =
                    entry != null && isAlive(now, entry.depositedAt, ttlBeforeAttempt);
            boolean credentialMatches =
                    aliveBeforeAttempt && entry.credential.equals(credential);

            if (aliveBeforeAttempt) {
                if (credentialMatches) {
                    ensureCounterAvailable(truePositives);
                    truePositives++;
                } else {
                    ensureCounterAvailable(falseNegatives);
                    falseNegatives++;
                }
            } else if (credential.length() != 0) {
                ensureCounterAvailable(falsePositives);
                falsePositives++;
            } else {
                ensureCounterAvailable(trueNegatives);
                trueNegatives++;
            }

            double ttlAfterAttempt = effectiveTtlLocked();
            boolean aliveAfterAttempt = aliveBeforeAttempt
                    && isAlive(now, entry.depositedAt, ttlAfterAttempt);

            if (entry != null && !aliveAfterAttempt) {
                removeEntry(entry);
            }
            if (credentialMatches && aliveAfterAttempt) {
                return entry.payload.clone();
            }
            return null;
        }
    }

    public void cleanup() {
        synchronized (lock) {
            long now = captureTime();
            double ttl = effectiveTtlLocked();
            Entry previous = null;
            Entry current = head;
            while (current != null) {
                Entry next = current.next;
                if (!isAlive(now, current.depositedAt, ttl)) {
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
        synchronized (lock) {
            double f1 = f1Locked();
            return new Metrics(truePositives, falsePositives, falseNegatives,
                    trueNegatives, f1, baseTtl * f1, storedEntryCount);
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

    private long captureTime() {
        long now = clock.now();
        if (now < 0) {
            throw new IllegalStateException("clock returned a negative time");
        }
        return now;
    }

    private boolean isAlive(long now, long depositedAt, double ttl) {
        return (double) (now - depositedAt) < ttl;
    }

    private double effectiveTtlLocked() {
        return baseTtl * f1Locked();
    }

    private double f1Locked() {
        double twiceTruePositives = 2.0 * (double) truePositives;
        double denominator = twiceTruePositives
                + (double) falsePositives + (double) falseNegatives;
        return denominator == 0.0 ? 1.0 : twiceTruePositives / denominator;
    }

    private void ensureCounterAvailable(long counter) {
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

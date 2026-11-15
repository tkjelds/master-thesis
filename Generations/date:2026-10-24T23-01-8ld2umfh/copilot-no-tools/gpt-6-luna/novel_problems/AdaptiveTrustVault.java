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

    private static final int TP = 0;
    private static final int FP = 1;
    private static final int FN = 2;
    private static final int TN = 3;

    private static final class Entry {
        final String key;
        final String credential;
        final byte[] payload;
        final long depositTime;
        Entry next;

        Entry(String key, String credential, byte[] payload,
              long depositTime, Entry next) {
            this.key = key;
            this.credential = credential;
            this.payload = payload;
            this.depositTime = depositTime;
            this.next = next;
        }
    }

    private final Object lock = new Object();
    private final long baseTtl;
    private final Clock clock;
    private Entry entries;

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
            throw new IllegalArgumentException("credential must not be empty");
        }

        byte[] payloadCopy = copy(payload);

        synchronized (lock) {
            long depositTime = currentTime();
            Entry previous = null;
            Entry current = entries;

            while (current != null) {
                if (current.key.equals(key)) {
                    Entry replacement = new Entry(
                        key, credential, payloadCopy, depositTime, current.next
                    );
                    if (previous == null) {
                        entries = replacement;
                    } else {
                        previous.next = replacement;
                    }
                    return;
                }
                previous = current;
                current = current.next;
            }

            entries = new Entry(key, credential, payloadCopy, depositTime, entries);
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
            long now = currentTime();
            double preAttemptTtl = effectiveTtl();
            Entry previous = null;
            Entry entry = entries;

            while (entry != null && !entry.key.equals(key)) {
                previous = entry;
                entry = entry.next;
            }

            boolean aliveBeforeAttempt = entry != null
                && isAlive(now - entry.depositTime, preAttemptTtl);
            boolean credentialMatches = aliveBeforeAttempt
                && entry.credential.equals(credential);

            int classification;
            if (aliveBeforeAttempt) {
                classification = credentialMatches ? TP : FN;
            } else {
                classification = credential.length() == 0 ? TN : FP;
            }

            ensureCounterCanIncrement(classification);
            incrementCounter(classification);

            double postAttemptTtl = effectiveTtl();
            boolean deliver = false;

            if (entry != null) {
                boolean aliveAfterAttempt = aliveBeforeAttempt
                    && isAlive(now - entry.depositTime, postAttemptTtl);

                if (!aliveAfterAttempt) {
                    removeEntry(previous, entry);
                } else if (credentialMatches) {
                    deliver = true;
                }
            }

            return deliver ? copy(entry.payload) : null;
        }
    }

    public void cleanup() {
        synchronized (lock) {
            long now = currentTime();
            double ttl = effectiveTtl();
            Entry previous = null;
            Entry current = entries;

            while (current != null) {
                Entry next = current.next;
                if (!isAlive(now - current.depositTime, ttl)) {
                    if (previous == null) {
                        entries = next;
                    } else {
                        previous.next = next;
                    }
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
            double ttl = baseTtl * f1;
            long count = 0;

            for (Entry current = entries; current != null; current = current.next) {
                if (count == Long.MAX_VALUE) {
                    throw new ArithmeticException("stored-entry count exhausted");
                }
                count++;
            }

            return new Metrics(
                truePositives,
                falsePositives,
                falseNegatives,
                trueNegatives,
                f1,
                ttl,
                count
            );
        }
    }

    private long currentTime() {
        long now = clock.now();
        if (now < 0) {
            throw new IllegalStateException("clock returned a negative time");
        }
        return now;
    }

    private boolean isAlive(long age, double ttl) {
        return (double) age < ttl;
    }

    private double f1() {
        double numerator = 2.0 * (double) truePositives;
        double denominator = numerator
            + (double) falsePositives
            + (double) falseNegatives;
        return denominator == 0.0 ? 1.0 : numerator / denominator;
    }

    private double effectiveTtl() {
        return baseTtl * f1();
    }

    private void ensureCounterCanIncrement(int classification) {
        if ((classification == TP && truePositives == Long.MAX_VALUE)
                || (classification == FP && falsePositives == Long.MAX_VALUE)
                || (classification == FN && falseNegatives == Long.MAX_VALUE)
                || (classification == TN && trueNegatives == Long.MAX_VALUE)) {
            throw new ArithmeticException("metric counter exhausted");
        }
    }

    private void incrementCounter(int classification) {
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

    private void removeEntry(Entry previous, Entry entry) {
        if (previous == null) {
            entries = entry.next;
        } else {
            previous.next = entry.next;
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

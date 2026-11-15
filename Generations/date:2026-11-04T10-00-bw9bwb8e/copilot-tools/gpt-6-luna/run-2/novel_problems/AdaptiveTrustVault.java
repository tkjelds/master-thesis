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
        public final long storedEntryCount;

        private Metrics(long tp, long fp, long fn, long tn, double f1,
                       double effectiveTTL, long storedEntryCount) {
            this.tp = tp;
            this.fp = fp;
            this.fn = fn;
            this.tn = tn;
            this.f1 = f1;
            this.effectiveTTL = effectiveTTL;
            this.storedEntryCount = storedEntryCount;
        }

        public long getTP() {
            return tp;
        }

        public long getFP() {
            return fp;
        }

        public long getFN() {
            return fn;
        }

        public long getTN() {
            return tn;
        }

        public double getF1() {
            return f1;
        }

        public double getEffectiveTTL() {
            return effectiveTTL;
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

        private Entry(String key, String credential, byte[] payload, long depositedAt) {
            this.key = key;
            this.credential = credential;
            this.payload = payload;
            this.depositedAt = depositedAt;
        }
    }

    private final long baseTTL;
    private final Clock clock;
    private Entry head;
    private long tp;
    private long fp;
    private long fn;
    private long tn;
    private long storedEntryCount;

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
        requireNonNull(key, "key");
        requireNonNull(credential, "credential");
        requireNonNull(payload, "payload");
        if (credential.length() == 0) {
            throw new IllegalArgumentException("credential must not be empty");
        }
        byte[] retainedPayload = copy(payload);

        synchronized (this) {
            long depositedAt = clock.now();
            Entry previous = null;
            Entry current = head;
            while (current != null && !current.key.equals(key)) {
                previous = current;
                current = current.next;
            }

            if (current == null && storedEntryCount == Long.MAX_VALUE) {
                throw new ArithmeticException("stored-entry count exhausted");
            }

            Entry replacement = new Entry(key, credential, retainedPayload, depositedAt);
            if (current == null) {
                if (previous == null) {
                    head = replacement;
                } else {
                    previous.next = replacement;
                }
                storedEntryCount++;
            } else {
                replacement.next = current.next;
                if (previous == null) {
                    head = replacement;
                } else {
                    previous.next = replacement;
                }
            }
        }
    }

    public byte[] attemptRetrieve(String key, String credential) {
        requireNonNull(key, "key");
        requireNonNull(credential, "credential");

        synchronized (this) {
            long now = clock.now();
            double ttlBeforeAttempt = effectiveTTL();
            Entry previous = null;
            Entry entry = head;
            while (entry != null && !entry.key.equals(key)) {
                previous = entry;
                entry = entry.next;
            }

            boolean aliveBeforeAttempt = entry != null
                    && isAlive(now, entry, ttlBeforeAttempt);
            boolean credentialMatches = aliveBeforeAttempt
                    && entry.credential.equals(credential);

            if (aliveBeforeAttempt) {
                ensureCounterCanIncrement(credentialMatches ? tp : fn);
                if (credentialMatches) {
                    tp++;
                } else {
                    fn++;
                }
            } else if (credential.length() != 0) {
                ensureCounterCanIncrement(fp);
                fp++;
            } else {
                ensureCounterCanIncrement(tn);
                tn++;
            }

            if (entry != null && !aliveBeforeAttempt) {
                remove(previous, entry);
                entry = null;
            }

            double ttlAfterAttempt = effectiveTTL();
            boolean aliveAfterAttempt = entry != null
                    && isAlive(now, entry, ttlAfterAttempt);
            if (entry != null && !aliveAfterAttempt) {
                remove(previous, entry);
            }

            if (credentialMatches && aliveAfterAttempt) {
                return copy(entry.payload);
            }
            return null;
        }
    }

    public void cleanup() {
        synchronized (this) {
            long now = clock.now();
            double ttl = effectiveTTL();
            Entry previous = null;
            Entry current = head;
            while (current != null) {
                Entry next = current.next;
                if (!isAlive(now, current, ttl)) {
                    remove(previous, current);
                } else {
                    previous = current;
                }
                current = next;
            }
        }
    }

    public Metrics snapshotMetrics() {
        synchronized (this) {
            double f1 = f1();
            return new Metrics(tp, fp, fn, tn, f1, baseTTL * f1, storedEntryCount);
        }
    }

    private double effectiveTTL() {
        return baseTTL * f1();
    }

    private double f1() {
        double truePositiveTerm = 2.0 * tp;
        double denominator = truePositiveTerm + (double) fp + (double) fn;
        return denominator == 0.0 ? 1.0 : truePositiveTerm / denominator;
    }

    private static boolean isAlive(long now, Entry entry, double ttl) {
        return (double) (now - entry.depositedAt) < ttl;
    }

    private void remove(Entry previous, Entry entry) {
        if (previous == null) {
            head = entry.next;
        } else {
            previous.next = entry.next;
        }
        entry.next = null;
        storedEntryCount--;
    }

    private static void ensureCounterCanIncrement(long counter) {
        if (counter == Long.MAX_VALUE) {
            throw new ArithmeticException("attempt counter exhausted");
        }
    }

    private static void requireNonNull(Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
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

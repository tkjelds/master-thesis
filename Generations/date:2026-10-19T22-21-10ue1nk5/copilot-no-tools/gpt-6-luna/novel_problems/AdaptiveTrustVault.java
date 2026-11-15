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
    }

    private static final int TRUE_POSITIVE = 0;
    private static final int FALSE_POSITIVE = 1;
    private static final int FALSE_NEGATIVE = 2;
    private static final int TRUE_NEGATIVE = 3;

    private static final class Entry {
        private final String key;
        private String credential;
        private byte[] payload;
        private long depositedAt;
        private Entry next;

        private Entry(String key, String credential, byte[] payload, long depositedAt) {
            this.key = key;
            this.credential = credential;
            this.payload = payload;
            this.depositedAt = depositedAt;
        }
    }

    private final Object monitor = new Object();
    private final long baseTTL;
    private final Clock clock;

    private Entry head;
    private long storedEntryCount;
    private long tp;
    private long fp;
    private long fn;
    private long tn;
    private double f1 = 1.0;
    private double effectiveTTL;

    public AdaptiveTrustVault(long baseTTL, Clock clock) {
        if (baseTTL <= 0) {
            throw new IllegalArgumentException("baseTTL must be positive");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.baseTTL = baseTTL;
        this.clock = clock;
        this.effectiveTTL = baseTTL;
    }

    public void deposit(String key, String credential, byte[] payload) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        if (credential == null) {
            throw new IllegalArgumentException("credential must not be null");
        }
        if (credential.length() == 0) {
            throw new IllegalArgumentException("credential must not be empty");
        }
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }

        byte[] retainedPayload = payload.clone();

        synchronized (monitor) {
            long now = clock.now();
            Entry previous = null;
            Entry current = head;

            while (current != null && !current.key.equals(key)) {
                previous = current;
                current = current.next;
            }

            if (current != null) {
                current.credential = credential;
                current.payload = retainedPayload;
                current.depositedAt = now;
                return;
            }

            if (storedEntryCount == Long.MAX_VALUE) {
                throw new ArithmeticException("stored-entry count exhausted");
            }

            Entry added = new Entry(key, credential, retainedPayload, now);
            if (previous == null) {
                added.next = head;
                head = added;
            } else {
                added.next = previous.next;
                previous.next = added;
            }
            storedEntryCount++;
        }
    }

    public byte[] attemptRetrieve(String key, String credential) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        if (credential == null) {
            throw new IllegalArgumentException("credential must not be null");
        }

        synchronized (monitor) {
            long now = clock.now();
            Entry previous = null;
            Entry entry = head;

            while (entry != null && !entry.key.equals(key)) {
                previous = entry;
                entry = entry.next;
            }

            boolean aliveBeforeAttempt = entry != null
                    && isAlive(entry, now, effectiveTTL);
            int classification;

            if (aliveBeforeAttempt) {
                classification = entry.credential.equals(credential)
                        ? TRUE_POSITIVE
                        : FALSE_NEGATIVE;
            } else {
                classification = credential.length() == 0
                        ? TRUE_NEGATIVE
                        : FALSE_POSITIVE;
            }

            ensureCounterAvailable(classification);

            if (entry != null && !aliveBeforeAttempt) {
                removeEntry(previous, entry);
                entry = null;
            }

            incrementCounter(classification);
            recomputeTTL();

            if (entry == null) {
                return null;
            }

            if (!isAlive(entry, now, effectiveTTL)) {
                removeEntry(previous, entry);
                return null;
            }

            if (classification != TRUE_POSITIVE) {
                return null;
            }

            return entry.payload.clone();
        }
    }

    public void cleanup() {
        synchronized (monitor) {
            long now = clock.now();
            double ttl = effectiveTTL;
            Entry previous = null;
            Entry current = head;

            while (current != null) {
                Entry next = current.next;
                if (!isAlive(current, now, ttl)) {
                    removeEntry(previous, current);
                } else {
                    previous = current;
                }
                current = next;
            }
        }
    }

    public Metrics snapshotMetrics() {
        synchronized (monitor) {
            return new Metrics(tp, fp, fn, tn, f1, effectiveTTL, storedEntryCount);
        }
    }

    private boolean isAlive(Entry entry, long now, double ttl) {
        long age = now - entry.depositedAt;
        return (double) age < ttl;
    }

    private void ensureCounterAvailable(int classification) {
        long value;
        switch (classification) {
            case TRUE_POSITIVE:
                value = tp;
                break;
            case FALSE_POSITIVE:
                value = fp;
                break;
            case FALSE_NEGATIVE:
                value = fn;
                break;
            case TRUE_NEGATIVE:
                value = tn;
                break;
            default:
                throw new IllegalStateException("unknown classification");
        }
        if (value == Long.MAX_VALUE) {
            throw new ArithmeticException("attempt counter exhausted");
        }
    }

    private void incrementCounter(int classification) {
        switch (classification) {
            case TRUE_POSITIVE:
                tp++;
                break;
            case FALSE_POSITIVE:
                fp++;
                break;
            case FALSE_NEGATIVE:
                fn++;
                break;
            case TRUE_NEGATIVE:
                tn++;
                break;
            default:
                throw new IllegalStateException("unknown classification");
        }
    }

    private void recomputeTTL() {
        double numerator = 2.0 * (double) tp;
        double denominator = numerator + (double) fp + (double) fn;
        f1 = denominator == 0.0 ? 1.0 : numerator / denominator;
        effectiveTTL = (double) baseTTL * f1;
    }

    private void removeEntry(Entry previous, Entry entry) {
        if (previous == null) {
            head = entry.next;
        } else {
            previous.next = entry.next;
        }
        entry.next = null;
        storedEntryCount--;
    }
}

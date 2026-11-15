@AdaptiveTrustVault.ThreadSafe
public class AdaptiveTrustVault {
    @interface ThreadSafe {}

    public interface Clock {
        long now();
    }

    public static final class Metrics {
        private final long tp;
        private final long fp;
        private final long fn;
        private final long tn;
        private final double f1;
        private final double effectiveTTL;
        private final long storedEntryCount;

        public Metrics(long tp, long fp, long fn, long tn, double f1, double effectiveTTL, long storedEntryCount) {
            this.tp = tp;
            this.fp = fp;
            this.fn = fn;
            this.tn = tn;
            this.f1 = f1;
            this.effectiveTTL = effectiveTTL;
            this.storedEntryCount = storedEntryCount;
        }

        public long getTp() {
            return tp;
        }

        public long getFp() {
            return fp;
        }

        public long getFn() {
            return fn;
        }

        public long getTn() {
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
        private String credential;
        private byte[] payload;
        private long depositTime;
        private Entry next;

        private Entry(String key, String credential, byte[] payload, long depositTime) {
            this.key = key;
            this.credential = credential;
            this.payload = payload;
            this.depositTime = depositTime;
        }
    }

    private final Object monitor;
    private final long baseTTL;
    private final Clock clock;

    private Entry head;
    private long tp;
    private long fp;
    private long fn;
    private long tn;
    private double effectiveTTL;

    public AdaptiveTrustVault(long baseTTL, Clock clock) {
        if (baseTTL <= 0L) {
            throw new IllegalArgumentException("baseTTL must be positive");
        }
        if (clock == null) {
            throw new NullPointerException("clock");
        }

        this.monitor = new Object();
        this.baseTTL = baseTTL;
        this.clock = clock;
        this.effectiveTTL = (double) baseTTL;
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

        byte[] copy = copyPayload(payload);

        synchronized (monitor) {
            long now = clock.now();
            Entry existing = findEntryLocked(key);
            if (existing == null) {
                Entry entry = new Entry(key, credential, copy, now);
                if (head == null) {
                    head = entry;
                } else {
                    Entry current = head;
                    while (current.next != null) {
                        current = current.next;
                    }
                    current.next = entry;
                }
            } else {
                existing.credential = credential;
                existing.payload = copy;
                existing.depositTime = now;
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

        synchronized (monitor) {
            long now = clock.now();
            Entry entry = findEntryLocked(key);
            double ttlBefore = effectiveTTL;
            boolean aliveBefore = isAlive(entry, now, ttlBefore);

            classifyAndUpdateCounter(entry, credential, aliveBefore);

            recomputeEffectiveTTLLocked();

            Entry sameEntry = entry;
            boolean aliveAfter = sameEntry != null && isAlive(sameEntry, now, effectiveTTL);
            if (!aliveAfter && sameEntry != null) {
                removeEntryLocked(key);
                sameEntry = null;
            }

            if (sameEntry == null) {
                return null;
            }
            if (!credential.equals(sameEntry.credential)) {
                return null;
            }
            if (!isAlive(sameEntry, now, effectiveTTL)) {
                removeEntryLocked(key);
                return null;
            }
            return copyPayload(sameEntry.payload);
        }
    }

    public void cleanup() {
        synchronized (monitor) {
            long now = clock.now();
            double ttlSnapshot = effectiveTTL;
            Entry previous = null;
            Entry current = head;

            while (current != null) {
                Entry next = current.next;
                if (!isAlive(current, now, ttlSnapshot)) {
                    if (previous == null) {
                        head = next;
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
        synchronized (monitor) {
            double f1 = computeF1Locked();
            double ttl = baseTTL * f1;
            effectiveTTL = ttl;
            long count = countEntriesLocked();
            return new Metrics(tp, fp, fn, tn, f1, ttl, count);
        }
    }

    private void classifyAndUpdateCounter(Entry entry, String credential, boolean aliveBefore) {
        if (entry != null && aliveBefore) {
            if (credential.equals(entry.credential)) {
                incrementCounterLocked("tp");
            } else {
                incrementCounterLocked("fn");
            }
            return;
        }

        if (credential.length() > 0) {
            incrementCounterLocked("fp");
        } else {
            incrementCounterLocked("tn");
        }
    }

    private void incrementCounterLocked(String counterName) {
        switch (counterName) {
            case "tp":
                if (tp == Long.MAX_VALUE) {
                    throw new IllegalStateException("TP counter exhausted");
                }
                tp++;
                break;
            case "fp":
                if (fp == Long.MAX_VALUE) {
                    throw new IllegalStateException("FP counter exhausted");
                }
                fp++;
                break;
            case "fn":
                if (fn == Long.MAX_VALUE) {
                    throw new IllegalStateException("FN counter exhausted");
                }
                fn++;
                break;
            case "tn":
                if (tn == Long.MAX_VALUE) {
                    throw new IllegalStateException("TN counter exhausted");
                }
                tn++;
                break;
            default:
                throw new IllegalArgumentException("unknown counter: " + counterName);
        }
    }

    private void recomputeEffectiveTTLLocked() {
        effectiveTTL = baseTTL * computeF1Locked();
    }

    private double computeF1Locked() {
        double tp2 = 2.0 * (double) tp;
        double denominator = tp2 + (double) fp + (double) fn;
        if (denominator == 0.0) {
            return 1.0;
        }
        return tp2 / denominator;
    }

    private boolean isAlive(Entry entry, long now, double ttl) {
        if (entry == null) {
            return false;
        }
        long age = now - entry.depositTime;
        return age >= 0L && age < ttl;
    }

    private Entry findEntryLocked(String key) {
        Entry current = head;
        while (current != null) {
            if (current.key.equals(key)) {
                return current;
            }
            current = current.next;
        }
        return null;
    }

    private void removeEntryLocked(String key) {
        if (head == null) {
            return;
        }
        if (head.key.equals(key)) {
            head = head.next;
            return;
        }
        Entry previous = head;
        Entry current = head.next;
        while (current != null) {
            if (current.key.equals(key)) {
                previous.next = current.next;
                return;
            }
            previous = current;
            current = current.next;
        }
    }

    private long countEntriesLocked() {
        long count = 0L;
        Entry current = head;
        while (current != null) {
            count++;
            current = current.next;
        }
        return count;
    }

    private static byte[] copyPayload(byte[] payload) {
        byte[] copy = new byte[payload.length];
        System.arraycopy(payload, 0, copy, 0, payload.length);
        return copy;
    }
}

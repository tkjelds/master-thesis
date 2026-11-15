@AdaptiveTrustVault.ThreadSafe
public final class AdaptiveTrustVault {
    @interface ThreadSafe {}

    public interface Clock {
        long now();
    }

    public static final class Metrics {
        public final long TP;
        public final long FP;
        public final long FN;
        public final long TN;
        public final double F1;
        public final double effectiveTTL;
        public final long storedEntryCount;

        private Metrics(long TP, long FP, long FN, long TN, double F1,
                       double effectiveTTL, long storedEntryCount) {
            this.TP = TP;
            this.FP = FP;
            this.FN = FN;
            this.TN = TN;
            this.F1 = F1;
            this.effectiveTTL = effectiveTTL;
            this.storedEntryCount = storedEntryCount;
        }

        public long getTP() {
            return TP;
        }

        public long getFP() {
            return FP;
        }

        public long getFN() {
            return FN;
        }

        public long getTN() {
            return TN;
        }

        public double getF1() {
            return F1;
        }

        public double getEffectiveTTL() {
            return effectiveTTL;
        }

        public long getStoredEntryCount() {
            return storedEntryCount;
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

    private static final class Node {
        final String key;
        Entry entry;
        Node next;

        Node(String key, Entry entry, Node next) {
            this.key = key;
            this.entry = entry;
            this.next = next;
        }
    }

    private final Object lock = new Object();
    private final long baseTTL;
    private final Clock clock;
    private Node head;
    private long entryCount;
    private long tp;
    private long fp;
    private long fn;
    private long tn;

    public AdaptiveTrustVault(long baseTTL, Clock clock) {
        if (baseTTL <= 0) {
            throw new IllegalArgumentException("baseTTL must be positive");
        }
        if (clock == null) {
            throw new NullPointerException("clock");
        }
        this.baseTTL = baseTTL;
        this.clock = clock;
    }

    public void deposit(String key, String credential, byte[] payload) {
        if (key == null || credential == null || payload == null) {
            throw new NullPointerException("key, credential, and payload must not be null");
        }
        if (credential.length() == 0) {
            throw new IllegalArgumentException("credential must not be empty");
        }

        byte[] retainedPayload = payload.clone();
        synchronized (lock) {
            long time = clock.now();
            Node previous = null;
            Node current = head;
            while (current != null) {
                if (current.key.equals(key)) {
                    current.entry = new Entry(credential, retainedPayload, time);
                    return;
                }
                previous = current;
                current = current.next;
            }

            if (entryCount == Long.MAX_VALUE) {
                throw new ArithmeticException("stored-entry count exhausted");
            }
            Node inserted = new Node(key, new Entry(credential, retainedPayload, time), null);
            if (previous == null) {
                head = inserted;
            } else {
                previous.next = inserted;
            }
            entryCount++;
        }
    }

    public byte[] attemptRetrieve(String key, String credential) {
        if (key == null || credential == null) {
            throw new NullPointerException("key and credential must not be null");
        }

        synchronized (lock) {
            long time = clock.now();
            Node previous = null;
            Node current = head;
            while (current != null && !current.key.equals(key)) {
                previous = current;
                current = current.next;
            }

            Entry entry = current == null ? null : current.entry;
            double ttlBefore = effectiveTTLLocked();
            boolean aliveBefore = entry != null && isAlive(entry, time, ttlBefore);
            boolean matched = aliveBefore && credential.equals(entry.credential);

            int category;
            if (aliveBefore) {
                category = matched ? 0 : 2;
            } else {
                category = credential.length() == 0 ? 3 : 1;
            }
            checkCounterAvailable(category);

            switch (category) {
                case 0:
                    tp++;
                    break;
                case 1:
                    fp++;
                    break;
                case 2:
                    fn++;
                    break;
                default:
                    tn++;
                    break;
            }

            double ttlAfter = effectiveTTLLocked();
            boolean aliveAfter = aliveBefore && isAlive(entry, time, ttlAfter);
            if (current != null && !aliveAfter) {
                removeNode(previous, current);
            }

            if (matched && aliveAfter) {
                return entry.payload.clone();
            }
            return null;
        }
    }

    public void cleanup() {
        synchronized (lock) {
            long time = clock.now();
            double ttl = effectiveTTLLocked();
            Node previous = null;
            Node current = head;
            while (current != null) {
                Node next = current.next;
                if (!isAlive(current.entry, time, ttl)) {
                    removeNode(previous, current);
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
            return new Metrics(tp, fp, fn, tn, f1, baseTTL * f1, entryCount);
        }
    }

    private double f1Locked() {
        double numerator = 2.0 * tp;
        double denominator = numerator + (double) fp + (double) fn;
        return denominator == 0.0 ? 1.0 : numerator / denominator;
    }

    private double effectiveTTLLocked() {
        return baseTTL * f1Locked();
    }

    private static boolean isAlive(Entry entry, long time, double ttl) {
        return time - entry.depositTime < ttl;
    }

    private void checkCounterAvailable(int category) {
        long value;
        switch (category) {
            case 0:
                value = tp;
                break;
            case 1:
                value = fp;
                break;
            case 2:
                value = fn;
                break;
            default:
                value = tn;
                break;
        }
        if (value == Long.MAX_VALUE) {
            throw new ArithmeticException("metric counter exhausted");
        }
    }

    private void removeNode(Node previous, Node current) {
        if (previous == null) {
            head = current.next;
        } else {
            previous.next = current.next;
        }
        entryCount--;
    }
}

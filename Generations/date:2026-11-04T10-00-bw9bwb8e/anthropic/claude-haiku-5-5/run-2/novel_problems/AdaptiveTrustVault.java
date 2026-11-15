@AdaptiveTrustVault.ThreadSafe
public final class AdaptiveTrustVault {
    @interface ThreadSafe {}


    public interface Clock {
        long now();
    }

    public static final class MetricsSnapshot {
        private final long truePositives;
        private final long falsePositives;
        private final long falseNegatives;
        private final long trueNegatives;
        private final double f1;
        private final long effectiveTtl;
        private final int storedEntries;

        private MetricsSnapshot(long tp, long fp, long fn, long tn,
                                double f1, long effectiveTtl, int storedEntries) {
            this.truePositives = tp;
            this.falsePositives = fp;
            this.falseNegatives = fn;
            this.trueNegatives = tn;
            this.f1 = f1;
            this.effectiveTtl = effectiveTtl;
            this.storedEntries = storedEntries;
        }

        public long getTruePositives() { return truePositives; }
        public long getFalsePositives() { return falsePositives; }
        public long getFalseNegatives() { return falseNegatives; }
        public long getTrueNegatives() { return trueNegatives; }
        public double getF1() { return f1; }
        public long getEffectiveTtl() { return effectiveTtl; }
        public int getStoredEntries() { return storedEntries; }
    }

    private static final class Node {
        final String key;
        String credential;
        byte[] payload;
        long depositTime;
        Node next;

        Node(String key, String credential, byte[] payload, long depositTime, Node next) {
            this.key = key;
            this.credential = credential;
            this.payload = payload;
            this.depositTime = depositTime;
            this.next = next;
        }
    }

    private static final int CAT_TP = 0;
    private static final int CAT_FP = 1;
    private static final int CAT_FN = 2;
    private static final int CAT_TN = 3;

    private static final int MAX_BUCKETS = 1 << 30;

    private final long baseTtl;
    private final Clock clock;

    // All mutable state below is guarded by 'lock'.
    // Policy: no operation blocks. Operations complete immediately or fail
    // explicitly (IllegalStateException when a counter is exhausted), so no
    // wait/notify is needed. Every decision is made under a single monitor,
    // so retrieval and cleanup never observe mixed metric versions.
    private final Object lock = new Object();
    private Node[] buckets = new Node[16];
    private int size;
    private long tp;
    private long fp;
    private long fn;
    private long tn;

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
        if (key == null) throw new NullPointerException("key");
        if (credential == null) throw new NullPointerException("credential");
        if (payload == null) throw new NullPointerException("payload");
        if (credential.isEmpty()) {
            throw new IllegalArgumentException("credential must not be empty");
        }
        byte[] copy = payload.clone();
        synchronized (lock) {
            long now = clock.now();
            Node existing = find(key);
            if (existing != null) {
                existing.credential = credential;
                existing.payload = copy;
                existing.depositTime = now;
            } else {
                insert(key, credential, copy, now);
            }
        }
    }

    public byte[] attemptRetrieve(String key, String credential) {
        if (key == null) throw new NullPointerException("key");
        if (credential == null) throw new NullPointerException("credential");

        synchronized (lock) {
            // 1. Capture time.
            long now = clock.now();

            // 2. Determine liveness using the pre-attempt TTL.
            long oldTtl = currentTtl();
            Node node = find(key);
            boolean present = node != null;
            boolean alive = present && (now - node.depositTime) < oldTtl;
            boolean matches = alive && node.credential.equals(credential);

            // 3. Classify and verify counters can be incremented before mutating anything.
            int category;
            if (alive) {
                category = matches ? CAT_TP : CAT_FN;
            } else {
                category = credential.isEmpty() ? CAT_TN : CAT_FP;
            }
            ensureNotExhausted(category);

            switch (category) {
                case CAT_TP: tp++; break;
                case CAT_FP: fp++; break;
                case CAT_FN: fn++; break;
                default:     tn++; break;
            }

            // Expired entries encountered by retrieval are removed permanently.
            if (present && !alive) {
                remove(key);
            }

            // 4. Recompute TTL from the updated metrics.
            long newTtl = currentTtl();

            // 5. Recheck the same entry with the new TTL.
            byte[] result = null;
            if (alive) {
                boolean stillAlive = (now - node.depositTime) < newTtl;
                if (!stillAlive) {
                    remove(key);
                }
                if (matches && stillAlive) {
                    result = node.payload.clone();
                }
            }
            return result;
        }
    }

    public void cleanup() {
        synchronized (lock) {
            long now = clock.now();
            long ttl = currentTtl();
            for (int i = 0; i < buckets.length; i++) {
                Node prev = null;
                Node n = buckets[i];
                while (n != null) {
                    Node next = n.next;
                    if ((now - n.depositTime) >= ttl) {
                        if (prev == null) {
                            buckets[i] = next;
                        } else {
                            prev.next = next;
                        }
                        n.next = null;
                        size--;
                    } else {
                        prev = n;
                    }
                    n = next;
                }
            }
        }
    }

    public MetricsSnapshot snapshotMetrics() {
        synchronized (lock) {
            double f = computeF1();
            long ttl = currentTtl();
            return new MetricsSnapshot(tp, fp, fn, tn, f, ttl, size);
        }
    }

    // ----- helpers; all must be called while holding 'lock' -----

    private void ensureNotExhausted(int category) {
        long current;
        String name;
        switch (category) {
            case CAT_TP: current = tp; name = "TP"; break;
            case CAT_FP: current = fp; name = "FP"; break;
            case CAT_FN: current = fn; name = "FN"; break;
            default:     current = tn; name = "TN"; break;
        }
        if (current == Long.MAX_VALUE) {
            throw new IllegalStateException(name + " counter exhausted");
        }
    }

    private double computeF1() {
        if (tp == 0 && fp == 0 && fn == 0) {
            return 1.0;
        }
        double twoTp = 2.0 * (double) tp;
        double denom = twoTp + (double) fp + (double) fn;
        return twoTp / denom;
    }

    private long currentTtl() {
        double f = computeF1();
        double raw = (double) baseTtl * f;
        long ttl = (long) raw;
        if (ttl > baseTtl) ttl = baseTtl;
        if (ttl < 0) ttl = 0;
        return ttl;
    }

    private static int indexFor(String key, int length) {
        return (key.hashCode() & 0x7fffffff) % length;
    }

    private Node find(String key) {
        Node n = buckets[indexFor(key, buckets.length)];
        while (n != null) {
            if (n.key.equals(key)) {
                return n;
            }
            n = n.next;
        }
        return null;
    }

    private void insert(String key, String credential, byte[] payload, long depositTime) {
        int idx = indexFor(key, buckets.length);
        buckets[idx] = new Node(key, credential, payload, depositTime, buckets[idx]);
        size++;
        if ((long) size * 4 > (long) buckets.length * 3 && buckets.length < MAX_BUCKETS) {
            resize(buckets.length * 2);
        }
    }

    private void remove(String key) {
        int idx = indexFor(key, buckets.length);
        Node prev = null;
        Node n = buckets[idx];
        while (n != null) {
            if (n.key.equals(key)) {
                if (prev == null) {
                    buckets[idx] = n.next;
                } else {
                    prev.next = n.next;
                }
                n.next = null;
                size--;
                return;
            }
            prev = n;
            n = n.next;
        }
    }

    private void resize(int newLength) {
        Node[] newBuckets = new Node[newLength];
        for (int i = 0; i < buckets.length; i++) {
            Node n = buckets[i];
            while (n != null) {
                Node next = n.next;
                int idx = indexFor(n.key, newLength);
                n.next = newBuckets[idx];
                newBuckets[idx] = n;
                n = next;
            }
        }
        buckets = newBuckets;
    }
}

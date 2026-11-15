@AdaptiveTrustVault.ThreadSafe
public final class AdaptiveTrustVault {
    @interface ThreadSafe {}


    /** Injected monotonic, nonnegative, thread-safe, nonblocking clock. */
    public interface Clock {
        long now();
    }

    /** Immutable, coherent view of the vault's metrics and state. */
    public static final class Metrics {
        private final long truePositives;
        private final long falsePositives;
        private final long falseNegatives;
        private final long trueNegatives;
        private final double f1;
        private final long effectiveTtl;
        private final int storedEntries;

        private Metrics(long tp, long fp, long fn, long tn,
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

    /** Internal hash-chain node. */
    private static final class Node {
        final String key;
        String credential;
        byte[] payload;
        long depositTime;
        Node next;

        Node(String key, String credential, byte[] payload, long depositTime) {
            this.key = key;
            this.credential = credential;
            this.payload = payload;
            this.depositTime = depositTime;
        }
    }

    private static final int INITIAL_BUCKETS = 16;

    private final long baseTtl;
    private final Clock clock;
    private final Object lock = new Object();

    // All mutable state below is guarded by 'lock'.
    private Node[] buckets = new Node[INITIAL_BUCKETS];
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

    /**
     * Stores or atomically replaces the entry for key. Payload is copied.
     * Empty credentials are rejected.
     */
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
            Node n = findLocked(key);
            if (n != null) {
                n.credential = credential;
                n.payload = copy;
                n.depositTime = now;
            } else {
                int idx = indexOf(key, buckets.length);
                Node created = new Node(key, credential, copy, now);
                created.next = buckets[idx];
                buckets[idx] = created;
                size++;
                if (size > buckets.length * 2) {
                    resizeLocked(buckets.length * 2);
                }
            }
        }
    }

    /**
     * Performs one atomic retrieval decision. Returns a copy of the payload
     * only if the credential matches and the entry is alive under the
     * recomputed TTL; otherwise returns null. Fails with IllegalStateException
     * (before any mutation) if the counter that must be incremented is exhausted.
     */
    public byte[] attemptRetrieve(String key, String credential) {
        if (key == null) throw new NullPointerException("key");
        if (credential == null) throw new NullPointerException("credential");

        synchronized (lock) {
            long now = clock.now();
            long oldTtl = currentTtlLocked();

            Node n = findLocked(key);
            boolean present = n != null;
            boolean alive = present && (now - n.depositTime) < oldTtl;
            boolean credMatch = present && n.credential.equals(credential);

            // Classify and check for counter exhaustion before any mutation.
            if (alive) {
                if (credMatch) {
                    checkNotExhausted(tp);
                    tp++;
                } else {
                    checkNotExhausted(fn);
                    fn++;
                }
            } else {
                if (credential.isEmpty()) {
                    checkNotExhausted(tn);
                    tn++;
                } else {
                    checkNotExhausted(fp);
                    fp++;
                }
            }

            // Expired entry encountered: remove it so it is never resurrected.
            if (present && !alive) {
                removeLocked(key);
                return null;
            }

            // Recompute TTL with updated metrics and recheck the same entry.
            long newTtl = currentTtlLocked();
            boolean aliveNow = (now - n.depositTime) < newTtl;
            if (!aliveNow) {
                removeLocked(key);
                return null;
            }
            if (!credMatch) {
                return null;
            }
            return n.payload.clone();
        }
    }

    /** Removes all entries that are expired under one captured time and current TTL. */
    public void cleanup() {
        synchronized (lock) {
            long now = clock.now();
            long ttl = currentTtlLocked();
            for (int i = 0; i < buckets.length; i++) {
                Node prev = null;
                Node cur = buckets[i];
                while (cur != null) {
                    Node next = cur.next;
                    if (!((now - cur.depositTime) < ttl)) {
                        if (prev == null) {
                            buckets[i] = next;
                        } else {
                            prev.next = next;
                        }
                        cur.next = null;
                        size--;
                    } else {
                        prev = cur;
                    }
                    cur = next;
                }
            }
        }
    }

    /** Returns a coherent snapshot of metrics and state; payloads are not exposed. */
    public Metrics snapshotMetrics() {
        synchronized (lock) {
            double f1 = computeF1(tp, fp, fn);
            long ttl = (long) (baseTtl * f1);
            return new Metrics(tp, fp, fn, tn, f1, ttl, size);
        }
    }

    // ---- Helpers (caller must hold lock) ----

    private long currentTtlLocked() {
        double f1 = computeF1(tp, fp, fn);
        return (long) (baseTtl * f1);
    }

    private static double computeF1(long tp, long fp, long fn) {
        double num = 2.0 * (double) tp;
        double den = num + (double) fp + (double) fn;
        if (den == 0.0) {
            return 1.0;
        }
        return num / den;
    }

    private static void checkNotExhausted(long counter) {
        if (counter == Long.MAX_VALUE) {
            throw new IllegalStateException("metric counter exhausted");
        }
    }

    private static int indexOf(String key, int length) {
        return (key.hashCode() & 0x7fffffff) % length;
    }

    private Node findLocked(String key) {
        int idx = indexOf(key, buckets.length);
        for (Node n = buckets[idx]; n != null; n = n.next) {
            if (key.equals(n.key)) {
                return n;
            }
        }
        return null;
    }

    private void removeLocked(String key) {
        int idx = indexOf(key, buckets.length);
        Node prev = null;
        Node cur = buckets[idx];
        while (cur != null) {
            if (key.equals(cur.key)) {
                if (prev == null) {
                    buckets[idx] = cur.next;
                } else {
                    prev.next = cur.next;
                }
                cur.next = null;
                size--;
                return;
            }
            prev = cur;
            cur = cur.next;
        }
    }

    private void resizeLocked(int newLength) {
        Node[] newBuckets = new Node[newLength];
        for (int i = 0; i < buckets.length; i++) {
            Node cur = buckets[i];
            while (cur != null) {
                Node next = cur.next;
                int idx = indexOf(cur.key, newLength);
                cur.next = newBuckets[idx];
                newBuckets[idx] = cur;
                cur = next;
            }
        }
        buckets = newBuckets;
    }
}

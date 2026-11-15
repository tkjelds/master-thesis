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
        private final int storedEntries;

        private Metrics(long tp, long fp, long fn, long tn, double f1, double effectiveTtl, int storedEntries) {
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
        public double getEffectiveTtl() { return effectiveTtl; }
        public int getStoredEntries() { return storedEntries; }
    }

    private static final int KIND_TP = 0;
    private static final int KIND_FN = 1;
    private static final int KIND_FP = 2;
    private static final int KIND_TN = 3;

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

    // All mutable state is guarded by 'lock'. Operations never block on capacity:
    // the store is unbounded, and attempts on missing/expired keys complete immediately
    // with null, so no wait/notify is required.
    private final Object lock = new Object();
    private final long baseTtl;
    private final Clock clock;

    private long tp;
    private long fp;
    private long fn;
    private long tn;

    private Node[] buckets = new Node[16];
    private int size;

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
        if (credential.isEmpty()) throw new IllegalArgumentException("credential must not be empty");

        byte[] copy = payload.clone();
        synchronized (lock) {
            long now = clock.now();
            Node existing = find(key);
            if (existing != null) {
                existing.credential = credential;
                existing.payload = copy;
                existing.depositTime = now;
            } else {
                if (size + 1 > buckets.length * 3 / 4) {
                    resize(buckets.length * 2);
                }
                int idx = indexFor(key, buckets.length);
                buckets[idx] = new Node(key, credential, copy, now, buckets[idx]);
                size++;
            }
        }
    }

    public byte[] attemptRetrieve(String key, String credential) {
        if (key == null) throw new NullPointerException("key");
        if (credential == null) throw new NullPointerException("credential");

        synchronized (lock) {
            long now = clock.now();
            double ttlBefore = currentTtl();

            Node node = find(key);
            boolean aliveBefore = node != null && ((double) (now - node.depositTime)) < ttlBefore;
            boolean matches = aliveBefore && node.credential.equals(credential);

            int kind;
            if (aliveBefore) {
                kind = matches ? KIND_TP : KIND_FN;
            } else {
                kind = credential.isEmpty() ? KIND_TN : KIND_FP;
            }

            // Detect exhaustion before any mutation.
            ensureNotExhausted(kind);

            switch (kind) {
                case KIND_TP: tp++; break;
                case KIND_FN: fn++; break;
                case KIND_FP: fp++; break;
                default: tn++; break;
            }

            if (node == null) {
                return null;
            }
            if (!aliveBefore) {
                // Expired entry encountered: remove so it is never resurrected.
                removeNode(key);
                return null;
            }

            double ttlAfter = currentTtl();
            boolean aliveAfter = ((double) (now - node.depositTime)) < ttlAfter;
            if (!aliveAfter) {
                removeNode(key);
                return null;
            }
            if (!matches) {
                return null;
            }
            return node.payload.clone();
        }
    }

    public void cleanup() {
        synchronized (lock) {
            long now = clock.now();
            double ttl = currentTtl();
            for (int i = 0; i < buckets.length; i++) {
                Node prev = null;
                Node n = buckets[i];
                while (n != null) {
                    Node next = n.next;
                    boolean alive = ((double) (now - n.depositTime)) < ttl;
                    if (!alive) {
                        if (prev == null) {
                            buckets[i] = next;
                        } else {
                            prev.next = next;
                        }
                        size--;
                    } else {
                        prev = n;
                    }
                    n = next;
                }
            }
        }
    }

    public Metrics snapshotMetrics() {
        synchronized (lock) {
            double f1 = computeF1();
            double ttl = baseTtl * f1;
            return new Metrics(tp, fp, fn, tn, f1, ttl, size);
        }
    }

    // Caller must hold lock.
    private double computeF1() {
        if (tp == 0 && fp == 0 && fn == 0) {
            return 1.0;
        }
        double num = 2.0 * (double) tp;
        double den = num + (double) fp + (double) fn;
        return num / den;
    }

    // Caller must hold lock.
    private double currentTtl() {
        return baseTtl * computeF1();
    }

    // Caller must hold lock.
    private void ensureNotExhausted(int kind) {
        long current;
        String name;
        switch (kind) {
            case KIND_TP: current = tp; name = "truePositives"; break;
            case KIND_FN: current = fn; name = "falseNegatives"; break;
            case KIND_FP: current = fp; name = "falsePositives"; break;
            default: current = tn; name = "trueNegatives"; break;
        }
        if (current == Long.MAX_VALUE) {
            throw new IllegalStateException("counter exhausted: " + name);
        }
    }

    // Caller must hold lock.
    private Node find(String key) {
        int idx = indexFor(key, buckets.length);
        for (Node n = buckets[idx]; n != null; n = n.next) {
            if (n.key.equals(key)) {
                return n;
            }
        }
        return null;
    }

    // Caller must hold lock.
    private void removeNode(String key) {
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
                size--;
                return;
            }
            prev = n;
            n = n.next;
        }
    }

    // Caller must hold lock.
    private void resize(int newCapacity) {
        Node[] old = buckets;
        Node[] fresh = new Node[newCapacity];
        for (int i = 0; i < old.length; i++) {
            Node n = old[i];
            while (n != null) {
                Node next = n.next;
                int idx = indexFor(n.key, newCapacity);
                n.next = fresh[idx];
                fresh[idx] = n;
                n = next;
            }
        }
        buckets = fresh;
    }

    private static int indexFor(String key, int capacity) {
        return (key.hashCode() & 0x7fffffff) % capacity;
    }
}

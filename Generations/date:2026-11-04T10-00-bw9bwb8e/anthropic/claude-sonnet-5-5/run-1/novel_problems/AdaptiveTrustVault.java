interface Clock {
    long now();
}

@AdaptiveTrustVault.ThreadSafe
public class AdaptiveTrustVault {
    @interface ThreadSafe {}


    public static final class Metrics {
        public final long tp;
        public final long fp;
        public final long fn;
        public final long tn;
        public final double f1;
        public final double effectiveTtl;
        public final int entryCount;

        Metrics(long tp, long fp, long fn, long tn, double f1, double effectiveTtl, int entryCount) {
            this.tp = tp;
            this.fp = fp;
            this.fn = fn;
            this.tn = tn;
            this.f1 = f1;
            this.effectiveTtl = effectiveTtl;
            this.entryCount = entryCount;
        }

        public long getTp() { return tp; }
        public long getFp() { return fp; }
        public long getFn() { return fn; }
        public long getTn() { return tn; }
        public double getF1() { return f1; }
        public double getEffectiveTtl() { return effectiveTtl; }
        public int getEntryCount() { return entryCount; }

        @Override
        public String toString() {
            return "Metrics{tp=" + tp + ", fp=" + fp + ", fn=" + fn + ", tn=" + tn
                    + ", f1=" + f1 + ", effectiveTtl=" + effectiveTtl
                    + ", entryCount=" + entryCount + "}";
        }
    }

    private static final class Node {
        final String key;
        final int hash;
        String credential;
        byte[] payload;
        long depositTime;
        Node next;

        Node(String key, int hash) {
            this.key = key;
            this.hash = hash;
        }
    }

    private final Object lock = new Object();
    private final long baseTtl;
    private final Clock clock;

    // All below guarded by lock
    private Node[] table = new Node[16];
    private int size = 0;
    private long tp = 0, fp = 0, fn = 0, tn = 0;

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

    // ---- helpers (call only while holding lock) ----

    private double currentF1() {
        double denom = 2.0 * (double) tp + (double) fp + (double) fn;
        if (denom == 0.0) {
            return 1.0;
        }
        return (2.0 * (double) tp) / denom;
    }

    private double currentTtl() {
        return (double) baseTtl * currentF1();
    }

    private static boolean aliveAt(Node n, long now, double ttl) {
        long age = now - n.depositTime;
        if (age < 0) {
            age = 0;
        }
        return (double) age < ttl;
    }

    private static int hashOf(String key) {
        int h = key.hashCode();
        return h ^ (h >>> 16);
    }

    private Node find(String key) {
        int h = hashOf(key);
        Node n = table[h & (table.length - 1)];
        while (n != null) {
            if (n.hash == h && n.key.equals(key)) {
                return n;
            }
            n = n.next;
        }
        return null;
    }

    private void removeNode(Node target) {
        int idx = target.hash & (table.length - 1);
        Node prev = null;
        Node n = table[idx];
        while (n != null) {
            if (n == target) {
                if (prev == null) {
                    table[idx] = n.next;
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

    private void resizeIfNeeded() {
        if (size * 4 < table.length * 3) {
            return;
        }
        Node[] old = table;
        Node[] nt = new Node[old.length * 2];
        for (int i = 0; i < old.length; i++) {
            Node n = old[i];
            while (n != null) {
                Node nx = n.next;
                int idx = n.hash & (nt.length - 1);
                n.next = nt[idx];
                nt[idx] = n;
                n = nx;
            }
        }
        table = nt;
    }

    private static boolean constantTimeEquals(String a, String b) {
        int diff = a.length() ^ b.length();
        int len = Math.min(a.length(), b.length());
        for (int i = 0; i < len; i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    // ---- public API ----

    public void deposit(String key, String credential, byte[] payload) {
        if (key == null || credential == null || payload == null) {
            throw new NullPointerException("null argument");
        }
        if (credential.isEmpty()) {
            throw new IllegalArgumentException("empty credential not permitted");
        }
        byte[] copy = payload.clone();
        synchronized (lock) {
            long now = clock.now();
            Node n = find(key);
            if (n != null) {
                n.credential = credential;
                n.payload = copy;
                n.depositTime = now;
            } else {
                Node nn = new Node(key, hashOf(key));
                nn.credential = credential;
                nn.payload = copy;
                nn.depositTime = now;
                int idx = nn.hash & (table.length - 1);
                nn.next = table[idx];
                table[idx] = nn;
                size++;
                resizeIfNeeded();
            }
        }
    }

    public byte[] attemptRetrieve(String key, String credential) {
        if (key == null || credential == null) {
            throw new NullPointerException("null argument");
        }
        synchronized (lock) {
            long now = clock.now();
            Node entry = find(key);
            double oldTtl = currentTtl();
            boolean aliveOld = entry != null && aliveAt(entry, now, oldTtl);
            boolean match = aliveOld && constantTimeEquals(entry.credential, credential);

            // classify; check exhaustion before any mutation
            int kind; // 0 TP, 1 FN, 2 FP, 3 TN
            if (aliveOld) {
                kind = match ? 0 : 1;
            } else {
                kind = credential.isEmpty() ? 3 : 2;
            }
            long cur;
            switch (kind) {
                case 0: cur = tp; break;
                case 1: cur = fn; break;
                case 2: cur = fp; break;
                default: cur = tn; break;
            }
            if (cur == Long.MAX_VALUE) {
                throw new IllegalStateException("metric counter exhausted");
            }
            switch (kind) {
                case 0: tp++; break;
                case 1: fn++; break;
                case 2: fp++; break;
                default: tn++; break;
            }

            double newTtl = currentTtl();
            boolean aliveNew = entry != null && aliveOld && aliveAt(entry, now, newTtl);

            if (entry != null && (!aliveOld || !aliveNew)) {
                removeNode(entry);
            }
            if (match && aliveNew) {
                return entry.payload.clone();
            }
            return null;
        }
    }

    public int cleanup() {
        synchronized (lock) {
            long now = clock.now();
            double ttl = currentTtl();
            int removed = 0;
            for (int i = 0; i < table.length; i++) {
                Node prev = null;
                Node n = table[i];
                while (n != null) {
                    Node nx = n.next;
                    if (!aliveAt(n, now, ttl)) {
                        if (prev == null) {
                            table[i] = nx;
                        } else {
                            prev.next = nx;
                        }
                        size--;
                        removed++;
                    } else {
                        prev = n;
                    }
                    n = nx;
                }
            }
            return removed;
        }
    }

    public Metrics snapshotMetrics() {
        synchronized (lock) {
            double f1 = currentF1();
            return new Metrics(tp, fp, fn, tn, f1, (double) baseTtl * f1, size);
        }
    }
}

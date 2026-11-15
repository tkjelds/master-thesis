import java.lang.*;

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
    }

    private static final class Node {
        final String key;
        final String credential;
        final byte[] payload;
        final long depositTime;
        Node next;

        Node(String key, String credential, byte[] payload, long depositTime) {
            this.key = key;
            this.credential = credential;
            this.payload = payload;
            this.depositTime = depositTime;
        }
    }

    private static final long COUNTER_LIMIT = Long.MAX_VALUE / 4;

    private final Object lock = new Object();
    private final long baseTtl;
    private final Clock clock;

    // guarded by lock
    private Node[] table = new Node[16];
    private int size = 0;
    private long tp = 0, fp = 0, fn = 0, tn = 0;

    public AdaptiveTrustVault(long baseTtl, Clock clock) {
        if (baseTtl <= 0) throw new IllegalArgumentException("baseTtl must be positive");
        if (clock == null) throw new NullPointerException("clock");
        this.baseTtl = baseTtl;
        this.clock = clock;
    }

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
            Node n = new Node(key, credential, copy, now);
            int idx = indexFor(key, table.length);
            Node prev = null;
            Node cur = table[idx];
            while (cur != null) {
                if (cur.key.equals(key)) {
                    n.next = cur.next;
                    if (prev == null) table[idx] = n; else prev.next = n;
                    return;
                }
                prev = cur;
                cur = cur.next;
            }
            n.next = table[idx];
            table[idx] = n;
            size++;
            if (size > (table.length / 4) * 3) resize();
        }
    }

    public byte[] attemptRetrieve(String key, String credential) {
        if (key == null || credential == null) {
            throw new NullPointerException("null argument");
        }
        synchronized (lock) {
            long now = clock.now();
            int idx = indexFor(key, table.length);
            Node prev = null;
            Node entry = table[idx];
            while (entry != null && !entry.key.equals(key)) {
                prev = entry;
                entry = entry.next;
            }
            boolean aliveBefore = entry != null && isAlive(entry, now, tp, fp, fn);
            boolean match = false;
            int kind; // 0 TP, 1 FN, 2 FP, 3 TN
            if (aliveBefore) {
                match = credentialsEqual(entry.credential, credential);
                kind = match ? 0 : 1;
            } else {
                kind = credential.isEmpty() ? 3 : 2;
            }
            long current = kind == 0 ? tp : kind == 1 ? fn : kind == 2 ? fp : tn;
            if (current >= COUNTER_LIMIT) {
                throw new IllegalStateException("metric counter exhausted");
            }
            if (entry != null && !aliveBefore) {
                if (prev == null) table[idx] = entry.next; else prev.next = entry.next;
                entry.next = null;
                size--;
            }
            switch (kind) {
                case 0: tp++; break;
                case 1: fn++; break;
                case 2: fp++; break;
                default: tn++; break;
            }
            if (aliveBefore && match && isAlive(entry, now, tp, fp, fn)) {
                return entry.payload.clone();
            }
            return null;
        }
    }

    public int cleanup() {
        synchronized (lock) {
            long now = clock.now();
            long ctp = tp, cfp = fp, cfn = fn;
            int removed = 0;
            for (int i = 0; i < table.length; i++) {
                Node prev = null;
                Node cur = table[i];
                while (cur != null) {
                    Node next = cur.next;
                    if (!isAlive(cur, now, ctp, cfp, cfn)) {
                        if (prev == null) table[i] = next; else prev.next = next;
                        cur.next = null;
                        size--;
                        removed++;
                    } else {
                        prev = cur;
                    }
                    cur = next;
                }
            }
            return removed;
        }
    }

    public Metrics snapshotMetrics() {
        synchronized (lock) {
            long den = 2 * tp + fp + fn;
            double f1 = den == 0 ? 1.0 : (2.0 * (double) tp) / (double) den;
            double ttl = (double) baseTtl * f1;
            return new Metrics(tp, fp, fn, tn, f1, ttl, size);
        }
    }

    // Must hold lock.
    private boolean isAlive(Node e, long now, long ctp, long cfp, long cfn) {
        long age = now - e.depositTime;
        if (age < 0) age = 0;
        long den = 2 * ctp + cfp + cfn;
        if (den == 0) {
            return age < baseTtl;
        }
        // age < baseTtl * 2*ctp / den  <=>  age*den < baseTtl*2*ctp
        long lhsHi = Math.multiplyHigh(age, den);
        long lhsLo = age * den;
        long rhsHi = Math.multiplyHigh(baseTtl, 2 * ctp);
        long rhsLo = baseTtl * (2 * ctp);
        if (lhsHi != rhsHi) return lhsHi < rhsHi;
        return Long.compareUnsigned(lhsLo, rhsLo) < 0;
    }

    private static boolean credentialsEqual(String a, String b) {
        int diff = a.length() ^ b.length();
        int n = Math.min(a.length(), b.length());
        for (int i = 0; i < n; i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    private static int indexFor(String key, int length) {
        int h = key.hashCode();
        h ^= (h >>> 16);
        return h & (length - 1);
    }

    private void resize() {
        Node[] old = table;
        Node[] nt = new Node[old.length * 2];
        for (int i = 0; i < old.length; i++) {
            Node cur = old[i];
            while (cur != null) {
                Node next = cur.next;
                int idx = indexFor(cur.key, nt.length);
                cur.next = nt[idx];
                nt[idx] = cur;
                cur = next;
            }
        }
        table = nt;
    }
}

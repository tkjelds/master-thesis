import java.io.Serializable;

interface Clock {
    long now();
}

@AdaptiveTrustVault.ThreadSafe
public class AdaptiveTrustVault {
    @interface ThreadSafe {}


    public static final class Metrics implements Serializable {
        private static final long serialVersionUID = 1L;
        private final long tp, fp, fn, tn;
        private final double f1, effectiveTtl;
        private final int storedEntries;

        Metrics(long tp, long fp, long fn, long tn, double f1, double effectiveTtl, int storedEntries) {
            this.tp = tp;
            this.fp = fp;
            this.fn = fn;
            this.tn = tn;
            this.f1 = f1;
            this.effectiveTtl = effectiveTtl;
            this.storedEntries = storedEntries;
        }

        public long getTp() { return tp; }
        public long getFp() { return fp; }
        public long getFn() { return fn; }
        public long getTn() { return tn; }
        public double getF1() { return f1; }
        public double getEffectiveTtl() { return effectiveTtl; }
        public int getStoredEntries() { return storedEntries; }

        @Override
        public String toString() {
            return "Metrics{tp=" + tp + ", fp=" + fp + ", fn=" + fn + ", tn=" + tn
                    + ", f1=" + f1 + ", effectiveTtl=" + effectiveTtl
                    + ", storedEntries=" + storedEntries + "}";
        }
    }

    private static final class Entry {
        final String key;
        final String credential;
        final byte[] payload;
        final long depositTime;
        Entry next;

        Entry(String key, String credential, byte[] payload, long depositTime) {
            this.key = key;
            this.credential = credential;
            this.payload = payload;
            this.depositTime = depositTime;
        }
    }

    private final long baseTtl;
    private final Clock clock;
    private final Object lock = new Object();

    // All guarded by lock
    private Entry[] buckets = new Entry[16];
    private int size = 0;
    private long tp, fp, fn, tn;

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

    // ---- helpers (call with lock held) ----

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

    private static boolean aliveAt(Entry e, long now, double ttl) {
        if (e == null) return false;
        long age = now - e.depositTime;
        if (age < 0) age = 0;
        return (double) age < ttl;
    }

    private static int indexFor(String key, int length) {
        int h = key.hashCode();
        h ^= (h >>> 16);
        return (h & 0x7fffffff) % length;
    }

    private Entry find(String key) {
        Entry e = buckets[indexFor(key, buckets.length)];
        while (e != null) {
            if (e.key.equals(key)) return e;
            e = e.next;
        }
        return null;
    }

    private void remove(String key) {
        int idx = indexFor(key, buckets.length);
        Entry prev = null;
        Entry e = buckets[idx];
        while (e != null) {
            if (e.key.equals(key)) {
                if (prev == null) buckets[idx] = e.next;
                else prev.next = e.next;
                e.next = null;
                size--;
                return;
            }
            prev = e;
            e = e.next;
        }
    }

    private void put(Entry n) {
        int idx = indexFor(n.key, buckets.length);
        Entry prev = null;
        Entry e = buckets[idx];
        while (e != null) {
            if (e.key.equals(n.key)) {
                n.next = e.next;
                if (prev == null) buckets[idx] = n;
                else prev.next = n;
                e.next = null;
                return;
            }
            prev = e;
            e = e.next;
        }
        n.next = buckets[idx];
        buckets[idx] = n;
        size++;
        if (size > buckets.length * 2) {
            resize();
        }
    }

    private void resize() {
        Entry[] old = buckets;
        Entry[] nb = new Entry[old.length * 2];
        for (int i = 0; i < old.length; i++) {
            Entry e = old[i];
            while (e != null) {
                Entry nx = e.next;
                int idx = indexFor(e.key, nb.length);
                e.next = nb[idx];
                nb[idx] = e;
                e = nx;
            }
        }
        buckets = nb;
    }

    private static boolean credentialsEqual(String a, String b) {
        int la = a.length(), lb = b.length();
        int diff = la ^ lb;
        int n = la < lb ? la : lb;
        for (int i = 0; i < n; i++) {
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
            put(new Entry(key, credential, copy, now));
        }
    }

    public byte[] attemptRetrieve(String key, String credential) {
        if (key == null || credential == null) {
            throw new NullPointerException("null argument");
        }
        synchronized (lock) {
            long now = clock.now();
            double oldTtl = currentTtl();
            Entry e = find(key);
            boolean alive = aliveAt(e, now, oldTtl);
            boolean matched = alive && credentialsEqual(e.credential, credential);

            // Check exhaustion before any mutation
            if (alive) {
                if (matched) {
                    if (tp == Long.MAX_VALUE) throw new IllegalStateException("TP counter exhausted");
                } else {
                    if (fn == Long.MAX_VALUE) throw new IllegalStateException("FN counter exhausted");
                }
            } else if (!credential.isEmpty()) {
                if (fp == Long.MAX_VALUE) throw new IllegalStateException("FP counter exhausted");
            } else {
                if (tn == Long.MAX_VALUE) throw new IllegalStateException("TN counter exhausted");
            }

            if (e != null && !alive) {
                remove(key);
            }

            if (alive) {
                if (matched) tp++;
                else fn++;
            } else if (!credential.isEmpty()) {
                fp++;
            } else {
                tn++;
            }

            double newTtl = currentTtl();
            if (!alive) {
                return null;
            }
            boolean stillAlive = aliveAt(e, now, newTtl);
            if (!stillAlive) {
                remove(key);
                return null;
            }
            if (matched) {
                return e.payload.clone();
            }
            return null;
        }
    }

    public int cleanup() {
        synchronized (lock) {
            long now = clock.now();
            double ttl = currentTtl();
            int removed = 0;
            for (int i = 0; i < buckets.length; i++) {
                Entry prev = null;
                Entry e = buckets[i];
                while (e != null) {
                    Entry nx = e.next;
                    if (!aliveAt(e, now, ttl)) {
                        if (prev == null) buckets[i] = nx;
                        else prev.next = nx;
                        e.next = null;
                        size--;
                        removed++;
                    } else {
                        prev = e;
                    }
                    e = nx;
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

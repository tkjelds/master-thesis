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
        public final double effectiveTtl;
        public final long storedEntryCount;

        private Metrics(long tp, long fp, long fn, long tn,
                        double f1, double effectiveTtl, long storedEntryCount) {
            this.tp = tp;
            this.fp = fp;
            this.fn = fn;
            this.tn = tn;
            this.f1 = f1;
            this.effectiveTtl = effectiveTtl;
            this.storedEntryCount = storedEntryCount;
        }
    }

    private static final class Entry {
        final String key;
        String credential;
        byte[] payload;
        long depositTime;
        Entry next;

        Entry(String key, String credential, byte[] payload, long depositTime,
              Entry next) {
            this.key = key;
            this.credential = credential;
            this.payload = payload;
            this.depositTime = depositTime;
            this.next = next;
        }
    }

    private final Object lock = new Object();
    private final long baseTtl;
    private final Clock clock;

    private Entry entries;
    private long entryCount;
    private long tp;
    private long fp;
    private long fn;
    private long tn;

    public AdaptiveTrustVault(long baseTtl, Clock clock) {
        if (baseTtl <= 0) {
            throw new IllegalArgumentException("base TTL must be positive");
        }
        if (clock == null) {
            throw new NullPointerException("clock");
        }
        this.baseTtl = baseTtl;
        this.clock = clock;
    }

    /*
     * Operations do not wait for capacity or for an entry to appear. They may
     * wait only to acquire the vault's intrinsic lock.
     */
    public void deposit(String key, String credential, byte[] payload) {
        if (key == null) {
            throw new NullPointerException("key");
        }
        if (credential == null) {
            throw new NullPointerException("credential");
        }
        if (payload == null) {
            throw new NullPointerException("payload");
        }
        if (credential.length() == 0) {
            throw new IllegalArgumentException("deposit credential is empty");
        }

        byte[] copy = copyBytes(payload);
        synchronized (lock) {
            long now = clock.now();
            Entry current = entries;
            while (current != null && !current.key.equals(key)) {
                current = current.next;
            }

            if (current != null) {
                current.credential = credential;
                current.payload = copy;
                current.depositTime = now;
            } else {
                if (entryCount == Long.MAX_VALUE) {
                    throw new IllegalStateException("entry count exhausted");
                }
                entries = new Entry(key, credential, copy, now, entries);
                entryCount++;
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

        synchronized (lock) {
            long now = clock.now();
            Entry previous = null;
            Entry current = entries;
            while (current != null && !current.key.equals(key)) {
                previous = current;
                current = current.next;
            }

            boolean alive = current != null
                    && isAlive(now - current.depositTime, tp, fp, fn);
            boolean matched = alive && current.credential.equals(credential);

            // Stage the counter update so exhaustion is detected before mutation.
            long newTp = tp;
            long newFp = fp;
            long newFn = fn;
            long newTn = tn;
            if (alive) {
                if (matched) {
                    if (newTp == Long.MAX_VALUE) {
                        throw new IllegalStateException("TP counter exhausted");
                    }
                    newTp++;
                } else {
                    if (newFn == Long.MAX_VALUE) {
                        throw new IllegalStateException("FN counter exhausted");
                    }
                    newFn++;
                }
            } else if (credential.length() != 0) {
                if (newFp == Long.MAX_VALUE) {
                    throw new IllegalStateException("FP counter exhausted");
                }
                newFp++;
            } else {
                if (newTn == Long.MAX_VALUE) {
                    throw new IllegalStateException("TN counter exhausted");
                }
                newTn++;
            }

            // Recheck the same entry against the staged, post-attempt metrics.
            boolean stillAlive = alive
                    && isAlive(now - current.depositTime, newTp, newFp, newFn);
            byte[] result = matched && stillAlive ? copyBytes(current.payload) : null;

            tp = newTp;
            fp = newFp;
            fn = newFn;
            tn = newTn;

            if (current != null && (!alive || !stillAlive)) {
                if (previous == null) {
                    entries = current.next;
                } else {
                    previous.next = current.next;
                }
                entryCount--;
            }
            return result;
        }
    }

    public void cleanup() {
        synchronized (lock) {
            long now = clock.now();
            Entry previous = null;
            Entry current = entries;
            while (current != null) {
                Entry next = current.next;
                if (!isAlive(now - current.depositTime, tp, fp, fn)) {
                    if (previous == null) {
                        entries = next;
                    } else {
                        previous.next = next;
                    }
                    entryCount--;
                } else {
                    previous = current;
                }
                current = next;
            }
        }
    }

    public Metrics snapshotMetrics() {
        synchronized (lock) {
            double f1 = calculateF1(tp, fp, fn);
            return new Metrics(tp, fp, fn, tn, f1, ((double) baseTtl) * f1,
                    entryCount);
        }
    }

    private static byte[] copyBytes(byte[] source) {
        byte[] copy = new byte[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }

    private static double calculateF1(long tp, long fp, long fn) {
        if (tp == 0 && fp == 0 && fn == 0) {
            return 1.0;
        }
        double numerator = 2.0 * (double) tp;
        return numerator / (numerator + (double) fp + (double) fn);
    }

    /*
     * Compare age with the mathematical TTL exactly, including at long and
     * floating-point boundaries. The comparison is:
     *
     *   age * (2*TP + FP + FN) < baseTtl * (2*TP)
     *
     * Products use little-endian base-2^16 limbs, avoiding overflow.
     */
    private boolean isAlive(long age, long tp, long fp, long fn) {
        if (tp == 0 && fp == 0 && fn == 0) {
            return age < baseTtl;
        }
        if (tp == 0) {
            return false;
        }
        if (fp == 0 && fn == 0) {
            return age < baseTtl;
        }

        long[] numerator = new long[5];
        addLong(numerator, tp);
        addLong(numerator, tp);

        long[] denominator = new long[5];
        addLong(denominator, tp);
        addLong(denominator, tp);
        addLong(denominator, fp);
        addLong(denominator, fn);

        long[] left = multiply(age, denominator);
        long[] right = multiply(baseTtl, numerator);
        for (int i = left.length - 1; i >= 0; i--) {
            if (left[i] != right[i]) {
                return left[i] < right[i];
            }
        }
        return false;
    }

    private static void addLong(long[] limbs, long value) {
        long carry = 0;
        for (int i = 0; i < 4; i++) {
            long sum = limbs[i] + (value & 0xffffL) + carry;
            limbs[i] = sum & 0xffffL;
            carry = sum >>> 16;
            value >>>= 16;
        }
        limbs[4] += carry;
    }

    private static long[] multiply(long value, long[] factor) {
        long[] product = new long[9];
        for (int i = 0; i < 4; i++) {
            long limb = value & 0xffffL;
            value >>>= 16;
            for (int j = 0; j < factor.length; j++) {
                product[i + j] += limb * factor[j];
            }
        }
        for (int i = 0; i < product.length - 1; i++) {
            product[i + 1] += product[i] >>> 16;
            product[i] &= 0xffffL;
        }
        return product;
    }
}

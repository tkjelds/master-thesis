/**
 * An unbounded, monitor-protected vault. Operations never wait for entries or
 * capacity; they may wait to acquire the monitor. TTL and clock units agree.
 */
@AdaptiveTrustVault.ThreadSafe
public final class AdaptiveTrustVault {
    @interface ThreadSafe {}

    public interface Clock {
        long now();
    }

    /**
     * Immutable, coherent snapshot. F1 and effectiveTTL are floating-point
     * approximations; expiration decisions use the exact rational TTL.
     * storedEntryCount includes entries not yet removed by retrieval or cleanup.
     */
    public static final class Metrics {
        public final long tp;
        public final long fp;
        public final long fn;
        public final long tn;
        public final double f1;
        public final double effectiveTTL;
        public final long storedEntryCount;

        private Metrics(long tp, long fp, long fn, long tn,
                        double f1, double effectiveTTL, long storedEntryCount) {
            this.tp = tp;
            this.fp = fp;
            this.fn = fn;
            this.tn = tn;
            this.f1 = f1;
            this.effectiveTTL = effectiveTTL;
            this.storedEntryCount = storedEntryCount;
        }
    }

    private static final class Entry {
        final String key;
        final String credential;
        final byte[] payload;
        final long depositedAt;
        Entry next;

        Entry(String key, String credential, byte[] payload,
              long depositedAt, Entry next) {
            this.key = key;
            this.credential = credential;
            this.payload = payload;
            this.depositedAt = depositedAt;
            this.next = next;
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
            throw new IllegalArgumentException("deposit credential must not be empty");
        }
        byte[] retainedPayload = copy(payload);
        synchronized (monitor) {
            long now = clock.now();
            Entry previous = null;
            Entry entry = head;
            while (entry != null && !entry.key.equals(key)) {
                previous = entry;
                entry = entry.next;
            }
            if (entry == null) {
                if (storedEntryCount == Long.MAX_VALUE) {
                    throw new IllegalStateException("stored-entry count exhausted");
                }
                head = new Entry(key, credential, retainedPayload, now, head);
                storedEntryCount++;
            } else {
                Entry replacement =
                    new Entry(key, credential, retainedPayload, now, entry.next);
                if (previous == null) {
                    head = replacement;
                } else {
                    previous.next = replacement;
                }
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
            Entry previous = null;
            Entry entry = head;
            while (entry != null && !entry.key.equals(key)) {
                previous = entry;
                entry = entry.next;
            }

            boolean alive = entry != null && isAlive(entry, now);
            boolean matches = alive && entry.credential.equals(credential);

            // Check the selected counter before any counter or entry mutation.
            if (alive) {
                if (matches) {
                    requireIncrementable(tp);
                    tp++;
                } else {
                    requireIncrementable(fn);
                    fn++;
                }
            } else if (credential.length() != 0) {
                requireIncrementable(fp);
                fp++;
            } else {
                requireIncrementable(tn);
                tn++;
            }

            // Recheck with updated metrics and the same captured time.
            // Previously expired entries cannot be resurrected by TTL growth.
            if (entry != null && (!alive || !isAlive(entry, now))) {
                unlink(previous, entry);
                return null;
            }
            return matches ? copy(entry.payload) : null;
        }
    }

    public void cleanup() {
        synchronized (monitor) {
            long now = clock.now();
            Entry previous = null;
            Entry entry = head;
            while (entry != null) {
                Entry next = entry.next;
                if (!isAlive(entry, now)) {
                    unlink(previous, entry);
                } else {
                    previous = entry;
                }
                entry = next;
            }
        }
    }

    public Metrics snapshotMetrics() {
        synchronized (monitor) {
            double numerator = 2.0 * tp;
            double denominator = numerator + (double) fp + (double) fn;
            double f1 = denominator == 0.0 ? 1.0 : numerator / denominator;
            return new Metrics(tp, fp, fn, tn, f1, baseTTL * f1,
                               storedEntryCount);
        }
    }

    private static byte[] copy(byte[] source) {
        byte[] result = new byte[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i];
        }
        return result;
    }

    private static void requireIncrementable(long value) {
        if (value == Long.MAX_VALUE) {
            throw new IllegalStateException("retrieval counter exhausted");
        }
    }

    // Called only with the monitor held.
    private void unlink(Entry previous, Entry entry) {
        if (previous == null) {
            head = entry.next;
        } else {
            previous.next = entry.next;
        }
        storedEntryCount--;
    }

    // Called only with the monitor held. Compare the rational TTL exactly:
    // age < baseTTL * 2TP / (2TP + FP + FN)
    // iff (baseTTL - age) * 2TP > age * FP + age * FN.
    private boolean isAlive(Entry entry, long now) {
        long age = now - entry.depositedAt;
        if (age >= baseTTL) {
            return false;
        }
        if (tp == 0) {
            return fp == 0 && fn == 0;
        }

        // Products and sums fit in unsigned 128 bits, represented as two longs.
        long remaining = baseTTL - age;
        long leftLow = remaining * tp;
        long leftHigh = multiplyHigh(remaining, tp);
        leftHigh = (leftHigh << 1) | (leftLow >>> 63);
        leftLow <<= 1;

        long fpLow = age * fp;
        long fnLow = age * fn;
        long rightLow = fpLow + fnLow;
        long carry = unsignedLess(rightLow, fpLow) ? 1L : 0L;
        long rightHigh = multiplyHigh(age, fp) + multiplyHigh(age, fn) + carry;

        if (leftHigh != rightHigh) {
            return unsignedLess(rightHigh, leftHigh);
        }
        return unsignedLess(rightLow, leftLow);
    }

    private static boolean unsignedLess(long a, long b) {
        return (a ^ Long.MIN_VALUE) < (b ^ Long.MIN_VALUE);
    }

    // High 64 bits of a product of two nonnegative longs. Low-word overflow
    // is intentional; the partial products recover its unsigned high bits.
    private static long multiplyHigh(long a, long b) {
        long mask = 0xffffffffL;
        long aLow = a & mask;
        long aHigh = a >>> 32;
        long bLow = b & mask;
        long bHigh = b >>> 32;
        long lowProduct = aLow * bLow;
        long cross1 = aLow * bHigh;
        long cross2 = aHigh * bLow;
        long middle = (lowProduct >>> 32) + (cross1 & mask) + (cross2 & mask);
        return aHigh * bHigh + (cross1 >>> 32) + (cross2 >>> 32)
               + (middle >>> 32);
    }
}

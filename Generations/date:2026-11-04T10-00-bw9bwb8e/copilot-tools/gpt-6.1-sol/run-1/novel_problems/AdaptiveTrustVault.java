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

    /*
     * Storage is unbounded: operations never wait for capacity or entries.
     * Operations serialize through the monitor.
     */
    public void deposit(String key, String credential, byte[] payload) {
        if (key == null || credential == null || payload == null) {
            throw new NullPointerException("key, credential and payload are required");
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
                Entry replacement =
                        new Entry(key, credential, retainedPayload, now, head);
                head = replacement;
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
        if (key == null || credential == null) {
            throw new NullPointerException("key and credential are required");
        }

        synchronized (monitor) {
            long now = clock.now();
            Entry previous = null;
            Entry entry = head;

            while (entry != null && !entry.key.equals(key)) {
                previous = entry;
                entry = entry.next;
            }

            boolean wasAlive = entry != null && isAlive(entry, now);
            boolean matched = wasAlive && entry.credential.equals(credential);

            // Check exhaustion before changing either counters or stored entries.
            if (wasAlive) {
                if (matched) {
                    if (tp == Long.MAX_VALUE) {
                        throw new IllegalStateException("TP counter exhausted");
                    }
                    tp++;
                } else {
                    if (fn == Long.MAX_VALUE) {
                        throw new IllegalStateException("FN counter exhausted");
                    }
                    fn++;
                }
            } else if (credential.length() != 0) {
                if (fp == Long.MAX_VALUE) {
                    throw new IllegalStateException("FP counter exhausted");
                }
                fp++;
            } else {
                if (tn == Long.MAX_VALUE) {
                    throw new IllegalStateException("TN counter exhausted");
                }
                tn++;
            }

            // isAlive now uses the updated counters and thus the new TTL.
            boolean remainsAlive = wasAlive && isAlive(entry, now);
            if (entry != null && !remainsAlive) {
                unlink(previous, entry);
            }

            return matched && remainsAlive ? copy(entry.payload) : null;
        }
    }

    public void cleanup() {
        synchronized (monitor) {
            long now = clock.now();
            Entry previous = null;
            Entry entry = head;

            while (entry != null) {
                Entry next = entry.next;
                if (isAlive(entry, now)) {
                    previous = entry;
                } else {
                    unlink(previous, entry);
                }
                entry = next;
            }
        }
    }

    public Metrics snapshotMetrics() {
        synchronized (monitor) {
            double twiceTP = 2.0 * tp;
            double denominator = twiceTP + (double) fp + (double) fn;
            double f1 = denominator == 0.0 ? 1.0 : twiceTP / denominator;
            return new Metrics(tp, fp, fn, tn, f1,
                    baseTTL * f1, storedEntryCount);
        }
    }

    private void unlink(Entry previous, Entry entry) {
        if (previous == null) {
            head = entry.next;
        } else {
            previous.next = entry.next;
        }
        storedEntryCount--;
    }

    private boolean isAlive(Entry entry, long now) {
        long age = now - entry.depositedAt;

        if (age >= baseTTL) {
            return false;
        }
        if (fp == 0 && fn == 0) {
            return true;
        }
        if (tp == 0) {
            return false;
        }
        if (age == 0) {
            return true;
        }

        /*
         * Compare the rational TTL exactly, without floating-point rounding:
         * age * (FP + FN) < (baseTTL - age) * 2 * TP.
         * Eight base-65536 limbs hold every possible intermediate value.
         */
        long[] left = new long[8];
        long[] right = new long[8];
        addProduct(left, age, fp);
        addProduct(left, age, fn);
        addProduct(right, baseTTL - age, tp);
        addProduct(right, baseTTL - age, tp);

        for (int i = 7; i >= 0; i--) {
            if (left[i] != right[i]) {
                return left[i] < right[i];
            }
        }
        return false;
    }

    private static void addProduct(long[] accumulator, long a, long b) {
        for (int i = 0; i < 4; i++) {
            long digit = (a >>> (16 * i)) & 65535L;
            long carry = 0;

            for (int j = 0; j < 4; j++) {
                int index = i + j;
                long otherDigit = (b >>> (16 * j)) & 65535L;
                long sum = accumulator[index] + digit * otherDigit + carry;
                accumulator[index] = sum & 65535L;
                carry = sum >>> 16;
            }

            int index = i + 4;
            while (carry != 0) {
                long sum = accumulator[index] + carry;
                accumulator[index] = sum & 65535L;
                carry = sum >>> 16;
                index++;
            }
        }
    }

    private static byte[] copy(byte[] source) {
        byte[] result = new byte[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i];
        }
        return result;
    }
}

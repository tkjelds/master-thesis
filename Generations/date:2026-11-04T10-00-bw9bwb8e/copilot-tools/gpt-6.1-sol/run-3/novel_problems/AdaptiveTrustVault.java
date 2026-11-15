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
    private double f1 = 1.0;
    private double effectiveTTL;

    /**
     * TTL and clock values use the same units. Storage has no fixed capacity.
     * Operations never wait for entries or capacity; they only acquire a monitor.
     */
    public AdaptiveTrustVault(long baseTTL, Clock clock) {
        if (baseTTL <= 0) {
            throw new IllegalArgumentException("baseTTL must be positive");
        }
        if (clock == null) {
            throw new NullPointerException("clock");
        }
        this.baseTTL = baseTTL;
        this.clock = clock;
        this.effectiveTTL = (double) baseTTL;
    }

    public void deposit(String key, String credential, byte[] payload) {
        if (key == null || credential == null || payload == null) {
            throw new NullPointerException("Deposit inputs must not be null");
        }
        if (credential.length() == 0) {
            throw new IllegalArgumentException("Credential must not be empty");
        }

        byte[] retainedPayload = copy(payload);
        synchronized (monitor) {
            Entry previous = null;
            Entry current = head;
            while (current != null && !current.key.equals(key)) {
                previous = current;
                current = current.next;
            }

            if (current == null && storedEntryCount == Long.MAX_VALUE) {
                throw new IllegalStateException("Stored-entry count exhausted");
            }

            long now = clock.now();
            Entry replacement = new Entry(
                    key, credential, retainedPayload, now,
                    current == null ? null : current.next);

            if (previous == null) {
                head = replacement;
            } else {
                previous.next = replacement;
            }
            if (current == null) {
                storedEntryCount++;
            }
        }
    }

    public byte[] attemptRetrieve(String key, String credential) {
        if (key == null || credential == null) {
            throw new NullPointerException("Retrieval inputs must not be null");
        }

        synchronized (monitor) {
            long now = clock.now();

            Entry previous = null;
            Entry current = head;
            while (current != null && !current.key.equals(key)) {
                previous = current;
                current = current.next;
            }

            boolean aliveBefore = current != null && isAlive(current, now);
            boolean matched = aliveBefore
                    && current.credential.equals(credential);

            // Check exhaustion before changing counters or removing an entry.
            if (aliveBefore) {
                if (matched) {
                    requireCounterSpace(tp);
                    tp++;
                } else {
                    requireCounterSpace(fn);
                    fn++;
                }
            } else if (credential.length() != 0) {
                requireCounterSpace(fp);
                fp++;
            } else {
                requireCounterSpace(tn);
                tn++;
            }

            recomputeTTL();

            if (current == null) {
                return null;
            }

            // An entry expired before the attempt cannot be resurrected.
            boolean aliveAfter = isAlive(current, now);
            if (!aliveBefore || !aliveAfter) {
                unlink(previous, current);
                return null;
            }

            return matched ? copy(current.payload) : null;
        }
    }

    public void cleanup() {
        synchronized (monitor) {
            long now = clock.now();
            Entry previous = null;
            Entry current = head;

            while (current != null) {
                Entry next = current.next;
                if (!isAlive(current, now)) {
                    unlink(previous, current);
                } else {
                    previous = current;
                }
                current = next;
            }
        }
    }

    public Metrics snapshotMetrics() {
        synchronized (monitor) {
            return new Metrics(
                    tp, fp, fn, tn, f1, effectiveTTL, storedEntryCount);
        }
    }

    private void recomputeTTL() {
        double numerator = 2.0 * (double) tp;
        double denominator = numerator + (double) fp + (double) fn;
        f1 = denominator == 0.0 ? 1.0 : numerator / denominator;
        effectiveTTL = (double) baseTTL * f1;
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
         * Compare age * (2*TP + FP + FN) < baseTTL * 2*TP exactly.
         * Eight base-65536 limbs cover both unsigned 128-bit products.
         * This avoids overflow and floating-point expiration-boundary errors.
         */
        long[] left = new long[8];
        long[] right = new long[8];
        addProduct(left, age, tp);
        addProduct(left, age, tp);
        addProduct(left, age, fp);
        addProduct(left, age, fn);
        addProduct(right, baseTTL, tp);
        addProduct(right, baseTTL, tp);

        for (int i = 7; i >= 0; i--) {
            if (left[i] != right[i]) {
                return left[i] < right[i];
            }
        }
        return false;
    }

    private static void addProduct(long[] result, long a, long b) {
        for (int i = 0; i < 4; i++) {
            long digitA = (a >>> (16 * i)) & 65535L;
            long carry = 0;

            for (int j = 0; j < 4; j++) {
                int position = i + j;
                long digitB = (b >>> (16 * j)) & 65535L;
                long value = result[position] + digitA * digitB + carry;
                result[position] = value & 65535L;
                carry = value >>> 16;
            }

            int position = i + 4;
            while (carry != 0) {
                long value = result[position] + carry;
                result[position] = value & 65535L;
                carry = value >>> 16;
                position++;
            }
        }
    }

    private void unlink(Entry previous, Entry current) {
        if (previous == null) {
            head = current.next;
        } else {
            previous.next = current.next;
        }
        storedEntryCount--;
    }

    private static void requireCounterSpace(long counter) {
        if (counter == Long.MAX_VALUE) {
            throw new IllegalStateException("Attempt counter exhausted");
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

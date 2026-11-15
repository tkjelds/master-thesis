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
        String credential;
        byte[] payload;
        long depositTime;
        Entry next;

        Entry(String key, String credential, byte[] payload,
              long depositTime, Entry next) {
            this.key = key;
            this.credential = credential;
            this.payload = payload;
            this.depositTime = depositTime;
            this.next = next;
        }
    }

    /*
     * Holds an exact rational TTL for expiration decisions. The public
     * metrics expose its floating-point approximation.
     */
    private static final class TtlState {
        final boolean defaultTtl;
        final boolean zeroTtl;
        final int[] denominator;
        final int[] baseTimesNumerator;
        final double f1;
        final double effectiveTTL;
        final long baseTTL;

        TtlState(long baseTTL, long tp, long fp, long fn) {
            this.baseTTL = baseTTL;
            this.defaultTtl = tp == 0 && fp == 0 && fn == 0;
            this.zeroTtl = !defaultTtl && tp == 0;

            if (defaultTtl) {
                denominator = null;
                baseTimesNumerator = null;
                f1 = 1.0;
                effectiveTTL = (double) baseTTL;
                return;
            }

            int[] numerator = new int[5];
            int[] divisor = new int[5];
            long carry = 0;

            for (int i = 0; i < numerator.length; i++) {
                long value = 2L * digit(tp, i) + carry;
                numerator[i] = (int) (value & 0xffffL);
                carry = value >>> 16;
            }

            carry = 0;
            for (int i = 0; i < divisor.length; i++) {
                long value = numerator[i] + digit(fp, i) + digit(fn, i) + carry;
                divisor[i] = (int) (value & 0xffffL);
                carry = value >>> 16;
            }

            denominator = divisor;
            baseTimesNumerator = multiply(numerator, baseTTL);

            double twiceTp = 2.0 * (double) tp;
            f1 = twiceTp / (twiceTp + (double) fp + (double) fn);
            effectiveTTL = (double) baseTTL * f1;
        }

        boolean isAlive(long age) {
            if (defaultTtl) {
                return age < baseTTL;
            }
            if (zeroTtl) {
                return false;
            }

            int[] ageTimesDenominator = multiply(denominator, age);
            for (int i = ageTimesDenominator.length - 1; i >= 0; i--) {
                if (ageTimesDenominator[i] != baseTimesNumerator[i]) {
                    return ageTimesDenominator[i] < baseTimesNumerator[i];
                }
            }
            return false;
        }

        private static int digit(long value, int index) {
            if (index >= 4) {
                return 0;
            }
            return (int) ((value >>> (index * 16)) & 0xffffL);
        }

        private static int[] multiply(int[] limbs, long value) {
            int[] result = new int[9];
            for (int i = 0; i < limbs.length; i++) {
                long carry = 0;
                for (int j = 0; j < 4; j++) {
                    int index = i + j;
                    long product = (long) limbs[i] * digit(value, j)
                            + result[index] + carry;
                    result[index] = (int) (product & 0xffffL);
                    carry = product >>> 16;
                }

                int index = i + 4;
                while (carry != 0) {
                    long sum = result[index] + carry;
                    result[index] = (int) (sum & 0xffffL);
                    carry = sum >>> 16;
                    index++;
                }
            }
            return result;
        }
    }

    private final Object lock = new Object();
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
     * The vault is unbounded apart from available memory and the representable
     * entry count. Operations do not wait for capacity or for entries.
     */
    public void deposit(String key, String credential, byte[] payload) {
        if (key == null || credential == null || payload == null) {
            throw new NullPointerException("key, credential, and payload are required");
        }
        if (credential.length() == 0) {
            throw new IllegalArgumentException("deposit credential must be nonempty");
        }

        byte[] copy = payload.clone();
        synchronized (lock) {
            Entry entry = head;
            while (entry != null && !entry.key.equals(key)) {
                entry = entry.next;
            }

            if (entry == null && storedEntryCount == Long.MAX_VALUE) {
                throw new IllegalStateException("entry count exhausted");
            }

            long now = clock.now();
            if (entry == null) {
                head = new Entry(key, credential, copy, now, head);
                storedEntryCount++;
            } else {
                entry.credential = credential;
                entry.payload = copy;
                entry.depositTime = now;
            }
        }
    }

    public byte[] attemptRetrieve(String key, String credential) {
        if (key == null || credential == null) {
            throw new NullPointerException("key and credential are required");
        }

        synchronized (lock) {
            long now = clock.now();
            TtlState before = new TtlState(baseTTL, tp, fp, fn);

            Entry previous = null;
            Entry entry = head;
            while (entry != null && !entry.key.equals(key)) {
                previous = entry;
                entry = entry.next;
            }

            boolean alive = entry != null
                    && before.isAlive(now - entry.depositTime);
            boolean matched = alive && entry.credential.equals(credential);

            if (alive) {
                if (matched) {
                    requireAvailable(tp);
                } else {
                    requireAvailable(fn);
                }
            } else if (credential.length() == 0) {
                requireAvailable(tn);
            } else {
                requireAvailable(fp);
            }

            if (entry != null && !alive) {
                unlink(previous, entry);
                entry = null;
            }

            if (alive) {
                if (matched) {
                    tp++;
                } else {
                    fn++;
                }
            } else if (credential.length() == 0) {
                tn++;
            } else {
                fp++;
            }

            TtlState after = new TtlState(baseTTL, tp, fp, fn);
            if (entry != null && !after.isAlive(now - entry.depositTime)) {
                unlink(previous, entry);
                entry = null;
            }

            if (matched && entry != null) {
                return entry.payload.clone();
            }
            return null;
        }
    }

    public void cleanup() {
        synchronized (lock) {
            long now = clock.now();
            TtlState ttl = new TtlState(baseTTL, tp, fp, fn);
            Entry previous = null;
            Entry entry = head;

            while (entry != null) {
                Entry next = entry.next;
                if (!ttl.isAlive(now - entry.depositTime)) {
                    unlink(previous, entry);
                } else {
                    previous = entry;
                }
                entry = next;
            }
        }
    }

    public Metrics snapshotMetrics() {
        synchronized (lock) {
            TtlState ttl = new TtlState(baseTTL, tp, fp, fn);
            return new Metrics(tp, fp, fn, tn, ttl.f1, ttl.effectiveTTL,
                    storedEntryCount);
        }
    }

    private static void requireAvailable(long counter) {
        if (counter == Long.MAX_VALUE) {
            throw new IllegalStateException("retrieval counter exhausted");
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
}

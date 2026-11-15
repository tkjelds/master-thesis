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

        Entry(String key, String credential, byte[] payload, long depositTime) {
            this.key = key;
            this.credential = credential;
            this.payload = payload;
            this.depositTime = depositTime;
        }
    }

    private static final long MAX_COUNTER = 9223372036854775807L;

    private final Object lock = new Object();
    private final long baseTtl;
    private final Clock clock;

    private Entry head;
    private long entryCount;
    private long tp;
    private long fp;
    private long fn;
    private long tn;

    // Scratch space for exact TTL comparisons, used only while holding lock.
    private final int[] numerator = new int[5];
    private final int[] denominator = new int[5];
    private final int[] leftProduct = new int[9];
    private final int[] rightProduct = new int[9];

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

    public void deposit(String key, String credential, byte[] payload) {
        if (key == null || credential == null || payload == null) {
            throw new NullPointerException("deposit arguments must not be null");
        }
        if (credential.length() == 0) {
            throw new IllegalArgumentException("deposit credential must not be empty");
        }

        byte[] payloadCopy = copyBytes(payload);
        synchronized (lock) {
            Entry entry = find(key);
            if (entry == null && entryCount == MAX_COUNTER) {
                throw new IllegalStateException("entry count exhausted");
            }

            long now = clock.now();
            if (entry == null) {
                Entry newEntry = new Entry(key, credential, payloadCopy, now);
                newEntry.next = head;
                head = newEntry;
                entryCount++;
            } else {
                entry.credential = credential;
                entry.payload = payloadCopy;
                entry.depositTime = now;
            }
        }
    }

    public byte[] attemptRetrieve(String key, String credential) {
        if (key == null || credential == null) {
            throw new NullPointerException("retrieval arguments must not be null");
        }

        synchronized (lock) {
            long now = clock.now();
            Entry previous = null;
            Entry entry = head;
            while (entry != null && !entry.key.equals(key)) {
                previous = entry;
                entry = entry.next;
            }

            boolean alive = entry != null && isAlive(entry, now);
            boolean matches = alive && entry.credential.equals(credential);

            // Check the selected counter before changing any vault state.
            if (alive) {
                if (matches) {
                    if (tp == MAX_COUNTER) {
                        throw new IllegalStateException("TP counter exhausted");
                    }
                } else if (fn == MAX_COUNTER) {
                    throw new IllegalStateException("FN counter exhausted");
                }
            } else if (credential.length() != 0) {
                if (fp == MAX_COUNTER) {
                    throw new IllegalStateException("FP counter exhausted");
                }
            } else if (tn == MAX_COUNTER) {
                throw new IllegalStateException("TN counter exhausted");
            }

            if (entry != null && !alive) {
                remove(previous, entry);
                entry = null;
            }

            if (alive) {
                if (matches) {
                    tp++;
                } else {
                    fn++;
                }
            } else if (credential.length() != 0) {
                fp++;
            } else {
                tn++;
            }

            // Recheck even a wrong-credential attempt, removing an entry that
            // became expired under the updated metrics.
            if (entry != null && !isAlive(entry, now)) {
                remove(previous, entry);
                entry = null;
            }

            if (matches && entry != null) {
                return copyBytes(entry.payload);
            }
            return null;
        }
    }

    public void cleanup() {
        synchronized (lock) {
            long now = clock.now();
            Entry previous = null;
            Entry entry = head;
            while (entry != null) {
                Entry next = entry.next;
                if (!isAlive(entry, now)) {
                    remove(previous, entry);
                } else {
                    previous = entry;
                }
                entry = next;
            }
        }
    }

    public Metrics snapshotMetrics() {
        synchronized (lock) {
            double f1;
            if (tp == 0 && fp == 0 && fn == 0) {
                f1 = 1.0;
            } else {
                double truePositivesTwice = 2.0 * (double) tp;
                f1 = truePositivesTwice
                        / (truePositivesTwice + (double) fp + (double) fn);
            }
            return new Metrics(tp, fp, fn, tn, f1,
                    (double) baseTtl * f1, entryCount);
        }
    }

    private Entry find(String key) {
        Entry entry = head;
        while (entry != null) {
            if (entry.key.equals(key)) {
                return entry;
            }
            entry = entry.next;
        }
        return null;
    }

    private void remove(Entry previous, Entry entry) {
        if (previous == null) {
            head = entry.next;
        } else {
            previous.next = entry.next;
        }
        entryCount--;
    }

    private static byte[] copyBytes(byte[] source) {
        byte[] copy = new byte[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }

    /*
     * Compare age with the exact rational TTL:
     * age * (2*TP + FP + FN) < baseTtl * (2*TP).
     * Base-2^16 limbs avoid overflow and avoid rounding at TTL boundaries.
     */
    private boolean isAlive(Entry entry, long now) {
        long age = now - entry.depositTime;
        if (tp == 0 && fp == 0 && fn == 0) {
            return age < baseTtl;
        }
        if (tp == 0) {
            return false;
        }

        clear(numerator);
        clear(denominator);
        addLong(numerator, tp);
        addLong(numerator, tp);
        addLong(denominator, tp);
        addLong(denominator, tp);
        addLong(denominator, fp);
        addLong(denominator, fn);

        multiply(age, denominator, leftProduct);
        multiply(baseTtl, numerator, rightProduct);
        for (int i = leftProduct.length - 1; i >= 0; i--) {
            if (leftProduct[i] != rightProduct[i]) {
                return leftProduct[i] < rightProduct[i];
            }
        }
        return false;
    }

    private static void clear(int[] limbs) {
        for (int i = 0; i < limbs.length; i++) {
            limbs[i] = 0;
        }
    }

    private static void addLong(int[] limbs, long value) {
        long carry = 0;
        for (int i = 0; i < 4; i++) {
            long sum = (long) limbs[i] + (value & 65535L) + carry;
            limbs[i] = (int) (sum & 65535L);
            carry = sum >>> 16;
            value >>>= 16;
        }
        limbs[4] += (int) carry;
    }

    private static void multiply(long value, int[] factor, int[] result) {
        clear(result);
        for (int i = 0; i < 4; i++) {
            long digit = (value >>> (16 * i)) & 65535L;
            long carry = 0;
            for (int j = 0; j < factor.length; j++) {
                long product = (long) result[i + j]
                        + digit * (long) factor[j] + carry;
                result[i + j] = (int) (product & 65535L);
                carry = product >>> 16;
            }
            result[i + factor.length] = (int) carry;
        }
    }
}

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

        private Metrics(long tp, long fp, long fn, long tn, double f1,
                        double effectiveTtl, long storedEntryCount) {
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
        final String credential;
        final byte[] payload;
        final long depositTime;
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
    private Entry head;
    private long tp;
    private long fp;
    private long fn;
    private long tn;
    private long storedEntryCount;

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

    // The structure is unbounded; operations wait only for the intrinsic lock.
    public void deposit(String key, String credential, byte[] payload) {
        if (key == null || credential == null || payload == null) {
            throw new NullPointerException("deposit arguments must not be null");
        }
        if (credential.length() == 0) {
            throw new IllegalArgumentException("deposit credential must not be empty");
        }
        byte[] copy = payload.clone();
        synchronized (lock) {
            long now = clock.now();
            Entry previous = null;
            Entry current = head;
            while (current != null && !current.key.equals(key)) {
                previous = current;
                current = current.next;
            }
            if (current == null && storedEntryCount == Long.MAX_VALUE) {
                throw new IllegalStateException("stored-entry count exhausted");
            }
            Entry replacement = new Entry(key, credential, copy, now,
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
            throw new NullPointerException("retrieval arguments must not be null");
        }
        int[] left = new int[9];
        int[] right = new int[9];
        synchronized (lock) {
            long now = clock.now();
            Entry previous = null;
            Entry current = head;
            while (current != null && !current.key.equals(key)) {
                previous = current;
                current = current.next;
            }
            boolean alive = current != null && isAlive(current, now, left, right);
            boolean matched = alive && current.credential.equals(credential);

            if (matched) {
                if (tp == Long.MAX_VALUE) {
                    throw new IllegalStateException("TP counter exhausted");
                }
            } else if (alive) {
                if (fn == Long.MAX_VALUE) {
                    throw new IllegalStateException("FN counter exhausted");
                }
            } else if (credential.length() != 0) {
                if (fp == Long.MAX_VALUE) {
                    throw new IllegalStateException("FP counter exhausted");
                }
            } else if (tn == Long.MAX_VALUE) {
                throw new IllegalStateException("TN counter exhausted");
            }

            byte[] result = matched ? current.payload.clone() : null;
            if (matched) {
                tp++;
            } else if (alive) {
                fn++;
            } else if (credential.length() != 0) {
                fp++;
            } else {
                tn++;
            }

            boolean stillAlive = alive && isAlive(current, now, left, right);
            if (current != null && !stillAlive) {
                if (previous == null) {
                    head = current.next;
                } else {
                    previous.next = current.next;
                }
                storedEntryCount--;
            }
            return matched && stillAlive ? result : null;
        }
    }

    public void cleanup() {
        int[] left = new int[9];
        int[] right = new int[9];
        synchronized (lock) {
            long now = clock.now();
            Entry previous = null;
            Entry current = head;
            while (current != null) {
                if (!isAlive(current, now, left, right)) {
                    if (previous == null) {
                        head = current.next;
                    } else {
                        previous.next = current.next;
                    }
                    storedEntryCount--;
                    current = current.next;
                } else {
                    previous = current;
                    current = current.next;
                }
            }
        }
    }

    public Metrics snapshotMetrics() {
        synchronized (lock) {
            double numerator = 2.0 * tp;
            double denominator = numerator + (double) fp + (double) fn;
            double f1 = denominator == 0.0 ? 1.0 : numerator / denominator;
            return new Metrics(tp, fp, fn, tn, f1, baseTtl * f1,
                    storedEntryCount);
        }
    }

    private boolean isAlive(Entry entry, long now, int[] left, int[] right) {
        long age = now - entry.depositTime;
        if (age >= baseTtl) {
            return false;
        }
        if (tp == 0 && fp == 0 && fn == 0) {
            return true;
        }
        if (tp == 0) {
            return false;
        }

        // Compare age * (2*TP + FP + FN) with baseTtl * 2*TP exactly.
        for (int i = 0; i < left.length; i++) {
            left[i] = 0;
            right[i] = 0;
        }
        addProduct(left, age, tp, 2);
        addProduct(left, age, fp, 1);
        addProduct(left, age, fn, 1);
        addProduct(right, baseTtl, tp, 2);
        for (int i = left.length - 1; i >= 0; i--) {
            if (left[i] != right[i]) {
                return left[i] < right[i];
            }
        }
        return false;
    }

    private static void addProduct(int[] digits, long a, long b, int factor) {
        for (int i = 0; i < 4; i++) {
            long aDigit = (a >>> (16 * i)) & 0xffffL;
            long carry = 0;
            for (int j = 0; j < 4; j++) {
                long bDigit = (b >>> (16 * j)) & 0xffffL;
                long value = digits[i + j] + aDigit * bDigit * factor + carry;
                digits[i + j] = (int) (value & 0xffffL);
                carry = value >>> 16;
            }
            int index = i + 4;
            while (carry != 0) {
                long value = digits[index] + carry;
                digits[index] = (int) (value & 0xffffL);
                carry = value >>> 16;
                index++;
            }
        }
    }
}

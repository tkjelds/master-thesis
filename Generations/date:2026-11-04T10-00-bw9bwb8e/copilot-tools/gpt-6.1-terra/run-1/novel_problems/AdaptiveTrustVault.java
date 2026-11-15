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

        private Metrics(long tp, long fp, long fn, long tn, double f1,
                        double effectiveTTL, long storedEntryCount) {
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
    private final long baseTTL;
    private final Clock clock;
    private Entry entries;
    private long tp;
    private long fp;
    private long fn;
    private long tn;
    private long storedEntryCount;

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

    // Operations serialize on the monitor; storage is limited only by memory.
    public void deposit(String key, String credential, byte[] payload) {
        if (key == null || credential == null || payload == null) {
            throw new NullPointerException("deposit arguments must not be null");
        }
        if (credential.length() == 0) {
            throw new IllegalArgumentException("deposit credential must not be empty");
        }
        byte[] copy = copyPayload(payload);
        synchronized (lock) {
            long now = clock.now();
            Entry previous = null;
            Entry current = entries;
            while (current != null && !current.key.equals(key)) {
                previous = current;
                current = current.next;
            }
            if (current == null && storedEntryCount == Long.MAX_VALUE) {
                throw new IllegalStateException("stored entry count exhausted");
            }
            Entry replacement = new Entry(key, credential, copy, now,
                    current == null ? entries : current.next);
            if (current == null) {
                entries = replacement;
                storedEntryCount++;
            } else if (previous == null) {
                entries = replacement;
            } else {
                previous.next = replacement;
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
            Entry current = entries;
            while (current != null && !current.key.equals(key)) {
                previous = current;
                current = current.next;
            }
            boolean alive = current != null && isAlive(current, now);
            boolean matches = alive && current.credential.equals(credential);

            if (alive) {
                if (matches) {
                    if (tp == Long.MAX_VALUE) {
                        throw new IllegalStateException("TP counter exhausted");
                    }
                } else if (fn == Long.MAX_VALUE) {
                    throw new IllegalStateException("FN counter exhausted");
                }
            } else if (credential.length() != 0) {
                if (fp == Long.MAX_VALUE) {
                    throw new IllegalStateException("FP counter exhausted");
                }
            } else if (tn == Long.MAX_VALUE) {
                throw new IllegalStateException("TN counter exhausted");
            }

            if (current != null && !alive) {
                remove(previous, current);
                current = null;
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

            if (current != null && !isAlive(current, now)) {
                remove(previous, current);
                current = null;
            }
            return matches && current != null ? copyPayload(current.payload) : null;
        }
    }

    public void cleanup() {
        synchronized (lock) {
            long now = clock.now();
            Entry previous = null;
            Entry current = entries;
            while (current != null) {
                Entry next = current.next;
                if (!isAlive(current, now)) {
                    remove(previous, current);
                } else {
                    previous = current;
                }
                current = next;
            }
        }
    }

    public Metrics snapshotMetrics() {
        synchronized (lock) {
            double numerator = 2.0 * tp;
            double denominator = numerator + (double) fp + (double) fn;
            double f1 = denominator == 0.0 ? 1.0 : numerator / denominator;
            return new Metrics(tp, fp, fn, tn, f1, baseTTL * f1,
                    storedEntryCount);
        }
    }

    private void remove(Entry previous, Entry current) {
        if (previous == null) {
            entries = current.next;
        } else {
            previous.next = current.next;
        }
        storedEntryCount--;
    }

    private static byte[] copyPayload(byte[] payload) {
        byte[] copy = new byte[payload.length];
        for (int i = 0; i < payload.length; i++) {
            copy[i] = payload[i];
        }
        return copy;
    }

    private boolean isAlive(Entry entry, long now) {
        long age = now - entry.depositTime;
        if (tp == 0) {
            return fp == 0 && fn == 0 && age < baseTTL;
        }

        // Compare age * (2*TP + FP + FN) with baseTTL * 2*TP exactly.
        int[] numerator = new int[5];
        add(numerator, tp);
        add(numerator, tp);
        int[] denominator = new int[5];
        for (int i = 0; i < numerator.length; i++) {
            denominator[i] = numerator[i];
        }
        add(denominator, fp);
        add(denominator, fn);
        int[] left = multiply(age, denominator);
        int[] right = multiply(baseTTL, numerator);
        for (int i = left.length - 1; i >= 0; i--) {
            if (left[i] != right[i]) {
                return left[i] < right[i];
            }
        }
        return false;
    }

    private static void add(int[] digits, long value) {
        long carry = 0;
        for (int i = 0; i < digits.length; i++) {
            long sum = digits[i] + (value & 0xffffL) + carry;
            digits[i] = (int) (sum & 0xffffL);
            carry = sum >>> 16;
            value >>>= 16;
        }
    }

    private static int[] multiply(long value, int[] digits) {
        int[] result = new int[9];
        for (int i = 0; i < 4; i++) {
            long factor = value & 0xffffL;
            long carry = 0;
            for (int j = 0; j < digits.length; j++) {
                long product = result[i + j] + factor * digits[j] + carry;
                result[i + j] = (int) (product & 0xffffL);
                carry = product >>> 16;
            }
            result[i + digits.length] = (int) carry;
            value >>>= 16;
        }
        return result;
    }
}

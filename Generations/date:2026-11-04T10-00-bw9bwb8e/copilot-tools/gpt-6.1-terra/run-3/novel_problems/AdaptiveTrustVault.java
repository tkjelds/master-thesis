@AdaptiveTrustVault.ThreadSafe
public final class AdaptiveTrustVault {
    @interface ThreadSafe {}

    public interface Clock {
        long now();
    }

    public static final class Metrics {
        public final long truePositives;
        public final long falsePositives;
        public final long falseNegatives;
        public final long trueNegatives;
        public final double f1;
        public final double effectiveTtl;
        public final long storedEntryCount;

        private Metrics(long truePositives, long falsePositives,
                        long falseNegatives, long trueNegatives,
                        double f1, double effectiveTtl, long storedEntryCount) {
            this.truePositives = truePositives;
            this.falsePositives = falsePositives;
            this.falseNegatives = falseNegatives;
            this.trueNegatives = trueNegatives;
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

        Entry(String key, String credential, byte[] payload,
              long depositTime, Entry next) {
            this.key = key;
            this.credential = credential;
            this.payload = payload;
            this.depositTime = depositTime;
            this.next = next;
        }
    }

    private static final class Ttl {
        final int[] numerator;
        final int[] denominator;
        final double f1;
        final double effectiveTtl;

        Ttl(long baseTtl, long tp, long fp, long fn) {
            numerator = new int[5];
            denominator = new int[5];

            if (tp == 0 && fp == 0 && fn == 0) {
                numerator[0] = 1;
                denominator[0] = 1;
                f1 = 1.0;
            } else {
                addLong(numerator, tp);
                addLong(numerator, tp);
                addLong(denominator, tp);
                addLong(denominator, tp);
                addLong(denominator, fp);
                addLong(denominator, fn);
                double twiceTp = 2.0 * (double) tp;
                f1 = twiceTp / (twiceTp + (double) fp + (double) fn);
            }
            effectiveTtl = (double) baseTtl * f1;
        }
    }

    private final Object monitor = new Object();
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
            throw new IllegalArgumentException("baseTtl must be positive");
        }
        if (clock == null) {
            throw new NullPointerException("clock");
        }
        this.baseTtl = baseTtl;
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
            throw new IllegalArgumentException("credential must not be empty");
        }

        byte[] copy = payload.clone();
        synchronized (monitor) {
            long now = clock.now();
            Entry previous = null;
            Entry current = entries;
            while (current != null && !current.key.equals(key)) {
                previous = current;
                current = current.next;
            }

            if (current == null && entryCount == Long.MAX_VALUE) {
                throw new IllegalStateException("entry count exhausted");
            }

            Entry replacement = new Entry(
                    key, credential, copy, now,
                    current == null ? entries : current.next);

            if (current == null) {
                entries = replacement;
                entryCount++;
            } else if (previous == null) {
                entries = replacement;
            } else {
                previous.next = replacement;
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
            Entry current = entries;
            while (current != null && !current.key.equals(key)) {
                previous = current;
                current = current.next;
            }

            Ttl before = new Ttl(baseTtl, tp, fp, fn);
            boolean alive = current != null && isAlive(current, now, before);
            boolean matched = alive && current.credential.equals(credential);

            long newTp = tp;
            long newFp = fp;
            long newFn = fn;
            long newTn = tn;

            if (alive) {
                if (matched) {
                    newTp = incrementCounter(tp);
                } else {
                    newFn = incrementCounter(fn);
                }
            } else if (credential.length() != 0) {
                newFp = incrementCounter(fp);
            } else {
                newTn = incrementCounter(tn);
            }

            Ttl after = new Ttl(baseTtl, newTp, newFp, newFn);
            boolean stillAlive = alive && isAlive(current, now, after);
            byte[] result = matched && stillAlive ? current.payload.clone() : null;

            tp = newTp;
            fp = newFp;
            fn = newFn;
            tn = newTn;

            if (current != null && !stillAlive) {
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
        synchronized (monitor) {
            long now = clock.now();
            Ttl ttl = new Ttl(baseTtl, tp, fp, fn);
            Entry previous = null;
            Entry current = entries;

            while (current != null) {
                Entry next = current.next;
                if (!isAlive(current, now, ttl)) {
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
        synchronized (monitor) {
            Ttl ttl = new Ttl(baseTtl, tp, fp, fn);
            return new Metrics(tp, fp, fn, tn, ttl.f1,
                    ttl.effectiveTtl, entryCount);
        }
    }

    private static long incrementCounter(long counter) {
        if (counter == Long.MAX_VALUE) {
            throw new IllegalStateException("metric counter exhausted");
        }
        return counter + 1;
    }

    private boolean isAlive(Entry entry, long now, Ttl ttl) {
        long age = now - entry.depositTime;

        // Compare age * denominator with baseTtl * numerator exactly.
        int[] left = multiply(age, ttl.denominator);
        int[] right = multiply(baseTtl, ttl.numerator);
        for (int i = left.length - 1; i >= 0; i--) {
            if (left[i] != right[i]) {
                return left[i] < right[i];
            }
        }
        return false;
    }

    private static void addLong(int[] digits, long value) {
        long carry = 0;
        for (int i = 0; i < digits.length; i++) {
            long sum = (long) digits[i] + (value & 0xffffL) + carry;
            digits[i] = (int) (sum & 0xffffL);
            carry = sum >>> 16;
            value >>>= 16;
        }
    }

    private static int[] multiply(long value, int[] digits) {
        long[] sums = new long[9];
        long remaining = value;
        for (int i = 0; i < 4; i++) {
            long part = remaining & 0xffffL;
            for (int j = 0; j < digits.length; j++) {
                sums[i + j] += part * digits[j];
            }
            remaining >>>= 16;
        }

        int[] result = new int[9];
        long carry = 0;
        for (int i = 0; i < result.length; i++) {
            long sum = sums[i] + carry;
            result[i] = (int) (sum & 0xffffL);
            carry = sum >>> 16;
        }
        return result;
    }
}

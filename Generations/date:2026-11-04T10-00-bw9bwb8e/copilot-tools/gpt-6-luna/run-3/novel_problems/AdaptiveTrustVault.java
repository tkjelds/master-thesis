@AdaptiveTrustVault.ThreadSafe
public final class AdaptiveTrustVault {
    @interface ThreadSafe {}

    public interface Clock {
        long now();
    }

    public static final class Metrics {
        public final long TP;
        public final long FP;
        public final long FN;
        public final long TN;
        public final long truePositives;
        public final long falsePositives;
        public final long falseNegatives;
        public final long trueNegatives;
        public final double f1;
        public final double effectiveTTL;
        public final int storedEntryCount;

        private Metrics(long tp, long fp, long fn, long tn, double f1,
                        double effectiveTTL, int storedEntryCount) {
            this.TP = tp;
            this.FP = fp;
            this.FN = fn;
            this.TN = tn;
            this.truePositives = tp;
            this.falsePositives = fp;
            this.falseNegatives = fn;
            this.trueNegatives = tn;
            this.f1 = f1;
            this.effectiveTTL = effectiveTTL;
            this.storedEntryCount = storedEntryCount;
        }

        public long getTP() {
            return TP;
        }

        public long getFP() {
            return FP;
        }

        public long getFN() {
            return FN;
        }

        public long getTN() {
            return TN;
        }

        public double getF1() {
            return f1;
        }

        public double getEffectiveTTL() {
            return effectiveTTL;
        }

        public int getStoredEntryCount() {
            return storedEntryCount;
        }
    }

    private final Object mutex = new Object();
    private final long baseTTL;
    private final Clock clock;

    private String[] keys = new String[8];
    private String[] credentials = new String[8];
    private byte[][] payloads = new byte[8][];
    private long[] depositTimes = new long[8];
    private int size;

    private long tp;
    private long fp;
    private long fn;
    private long tn;

    public AdaptiveTrustVault(long baseTTL, Clock clock) {
        if (baseTTL <= 0) {
            throw new IllegalArgumentException("baseTTL must be positive");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.baseTTL = baseTTL;
        this.clock = clock;
    }

    public void deposit(String key, String credential, byte[] payload) {
        if (key == null || credential == null || payload == null) {
            throw new IllegalArgumentException("inputs must not be null");
        }
        if (credential.length() == 0) {
            throw new IllegalArgumentException("deposit credential must not be empty");
        }

        byte[] retainedPayload = copy(payload);
        synchronized (mutex) {
            long now = readTime();
            int index = findKey(key);
            if (index >= 0) {
                credentials[index] = credential;
                payloads[index] = retainedPayload;
                depositTimes[index] = now;
                return;
            }

            ensureCapacity();
            keys[size] = key;
            credentials[size] = credential;
            payloads[size] = retainedPayload;
            depositTimes[size] = now;
            size++;
        }
    }

    public byte[] attemptRetrieve(String key, String credential) {
        if (key == null || credential == null) {
            throw new IllegalArgumentException("inputs must not be null");
        }

        synchronized (mutex) {
            long now = readTime();
            int index = findKey(key);
            double preAttemptTTL = effectiveTTL();
            boolean alive = index >= 0
                    && isAlive(now, depositTimes[index], preAttemptTTL);
            boolean matched = alive && credentials[index].equals(credential);

            if (alive) {
                if (matched) {
                    ensureCounterAvailable(tp);
                } else {
                    ensureCounterAvailable(fn);
                }
            } else if (credential.length() == 0) {
                ensureCounterAvailable(tn);
            } else {
                ensureCounterAvailable(fp);
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
                if (index >= 0) {
                    removeAt(index);
                }
            }

            double postAttemptTTL = effectiveTTL();
            if (!alive || !isAlive(now, depositTimes[index], postAttemptTTL)) {
                if (alive) {
                    removeAt(index);
                }
                return null;
            }

            return matched ? copy(payloads[index]) : null;
        }
    }

    public void cleanup() {
        synchronized (mutex) {
            long now = readTime();
            double ttl = effectiveTTL();
            int index = 0;
            while (index < size) {
                if (!isAlive(now, depositTimes[index], ttl)) {
                    removeAt(index);
                } else {
                    index++;
                }
            }
        }
    }

    public Metrics snapshotMetrics() {
        synchronized (mutex) {
            double f1 = currentF1();
            return new Metrics(tp, fp, fn, tn, f1, baseTTL * f1, size);
        }
    }

    private long readTime() {
        long now = clock.now();
        if (now < 0) {
            throw new IllegalStateException("clock returned a negative time");
        }
        return now;
    }

    private double currentF1() {
        double truePositiveTerm = 2.0 * (double) tp;
        double denominator = truePositiveTerm + (double) fp + (double) fn;
        return denominator == 0.0 ? 1.0 : truePositiveTerm / denominator;
    }

    private double effectiveTTL() {
        return baseTTL * currentF1();
    }

    private static boolean isAlive(long now, long depositedAt, double ttl) {
        return (double) (now - depositedAt) < ttl;
    }

    private int findKey(String key) {
        for (int i = 0; i < size; i++) {
            if (keys[i].equals(key)) {
                return i;
            }
        }
        return -1;
    }

    private void ensureCapacity() {
        if (size < keys.length) {
            return;
        }
        if (keys.length == Integer.MAX_VALUE) {
            throw new IllegalStateException("vault capacity exhausted");
        }

        int newCapacity = keys.length > (Integer.MAX_VALUE - 1) / 2
                ? Integer.MAX_VALUE
                : keys.length * 2;
        String[] newKeys = new String[newCapacity];
        String[] newCredentials = new String[newCapacity];
        byte[][] newPayloads = new byte[newCapacity][];
        long[] newDepositTimes = new long[newCapacity];

        for (int i = 0; i < size; i++) {
            newKeys[i] = keys[i];
            newCredentials[i] = credentials[i];
            newPayloads[i] = payloads[i];
            newDepositTimes[i] = depositTimes[i];
        }

        keys = newKeys;
        credentials = newCredentials;
        payloads = newPayloads;
        depositTimes = newDepositTimes;
    }

    private void removeAt(int index) {
        int last = size - 1;
        for (int i = index; i < last; i++) {
            keys[i] = keys[i + 1];
            credentials[i] = credentials[i + 1];
            payloads[i] = payloads[i + 1];
            depositTimes[i] = depositTimes[i + 1];
        }
        keys[last] = null;
        credentials[last] = null;
        payloads[last] = null;
        depositTimes[last] = 0;
        size--;
    }

    private static void ensureCounterAvailable(long counter) {
        if (counter == Long.MAX_VALUE) {
            throw new IllegalStateException("metric counter exhausted");
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

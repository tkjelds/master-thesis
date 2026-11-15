@AdaptiveTrustVault.ThreadSafe
class AdaptiveTrustVault {
    @interface ThreadSafe {}

    private final Clock clock;
    private final long baseTTL;
    private volatile int TP = 0, FP = 0, FN = 0, TN = 0;
    private volatile long TTL = 0;

    static class Entry {
        String credential;
        byte[] payload;
        long depositTime;

        Entry(String credential, byte[] payload) {
            this.credential = credential;
            this.payload = payload.clone();
            this.depositTime = System.currentTimeMillis();
        }
    }

    public AdaptiveTrustVault(long baseTTL, Clock clock) {
        if (baseTTL <= 0 || clock == null) throw new IllegalArgumentException();
        this.baseTTL = baseTTL;
        this.clock = clock;
    }

    public synchronized void deposit(String key, String credential, byte[] payload) {
        if (key == null || credential == null || payload == null) throw new IllegalArgumentException();
        Entry entry = new Entry(credential, payload);
        long now = clock.now();
        if (now >= TTL) cleanup(now);
        synchronized (this) {
            ttlUpdate(now, 0L);
        }
        TP += entry.depositTime < TTL ? 1 : -TP;
    }

    public synchronized byte[] attemptRetrieve(String key, String credential) {
        if (key == null || credential == null) throw new IllegalArgumentException();
        long now = clock.now();
        if (now >= TTL) cleanup(now);
        synchronized (this) {
            ttlUpdate(now, 1L);
        }
        TP += entry.depositTime < TTL && entry.credential.equals(credential) ? 1 : -TP;
        FN += entry.depositTime < TTL && !entry.credential.equals(credential) ? 1 : -FN;
        FP += entry.depositTime >= TTL && credential != null && !credential.isEmpty() ? 1 : -FP;
        TN += entry.depositTime >= TTL && credential == null || credential.isEmpty() ? 1 : -TN;
        return entry.depositTime < TTL && entry.credential.equals(credential) ? entry.payload.clone() : null;
    }

    public static synchronized void cleanup(long now, long TP, long FP, long FN, long TN) {
        if (now >= TTL) cleanup(now);
    }

    public static synchronized Metric snapshotMetrics() {
        return new Metric(TP, FP, FN, TN, F1, effectiveTTL, count);
    }

    private synchronized void ttlUpdate(long now, long delta) {
        TTL = baseTTL * (2 * TP + FP + FN == 0 ? 1.0 : 2 * delta / (2 * delta + FP + FN));
    }

    private static class Metric {
        final int TP, FP, FN, TN;
        final double F1, effectiveTTL, count;

        Metric(int TP, int FP, int FN, int TN, double F1, long effectiveTTL, int count) {
            this.TP = TP;
            this.FP = FP;
            this.FN = FN;
            this.TN = TN;
            this.F1 = F1;
            this.effectiveTTL = effectiveTTL;
            this.count = count;
        }
    }

    private interface Clock {
        long now();
    }
}

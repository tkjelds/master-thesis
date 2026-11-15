```java
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

class AdaptiveTrustVault {
    private final Clock clock;
    private final long baseTTL;
    private final AtomicInteger TP = new AtomicInteger(0);
    private final AtomicInteger FP = new AtomicInteger(0);
    private final AtomicInteger FN = new AtomicInteger(0);
    private final AtomicInteger TN = new AtomicInteger(0);

    static class Entry {
        String credential;
        byte[] payload;
        long depositTime;

        Entry(String credential, byte[] payload, long depositTime) {
            this.credential = credential;
            this.payload = payload.clone();
            this.depositTime = depositTime;
        }
    }

    private final Object lock = new Object();
    private Entry entry;

    public AdaptiveTrustVault(long baseTTL, Clock clock) {
        if (baseTTL <= 0 || clock == null) {
            throw new IllegalArgumentException("Invalid inputs");
        }
        this.baseTTL = baseTTL;
        this.clock = clock;
    }

    public void deposit(String key, String credential, byte[] payload) {
        if (key == null || credential == null || payload == null) {
            throw new IllegalArgumentException("Invalid inputs");
        }
        synchronized (lock) {
            entry = new Entry(credential, payload, clock.now());
            updateMetrics();
        }
    }

    public byte[] attemptRetrieve(String key, String credential) {
        if (key == null || credential == null) {
            throw new IllegalArgumentException("Invalid inputs");
        }
        long captureTime;
        boolean retrieved = false;
        byte[] payload = null;
        synchronized (lock) {
            if (entry != null && entry.depositTime + calculateTTL() > captureTime = clock.now()) {
                if (entry.credential.equals(credential)) {
                    TP.incrementAndGet();
                    payload = entry.payload.clone();
                    retrieved = true;
                } else {
                    FN.incrementAndGet();
                }
            } else {
                FP.incrementAndGet();
                entry = null;
            }
        }
        if (retrieved) {
            TN.incrementAndGet();
        } else {
            FP.incrementAndGet();
        }
        updateMetrics();
        return payload;
    }

    public void cleanup() {
        synchronized (lock) {
            if (entry != null && entry.depositTime + calculateTTL() <= clock.now()) {
                entry = null;
            }
        }
    }

    public Metrics snapshotMetrics() {
        synchronized (lock) {
            return new Metrics(TP.get(), FP.get(), FN.get(), TN.get(),
                    calculateEffectiveTTL(), entry != null ? 1 : 0);
        }
    }

    private void updateMetrics() {
        if (entry != null) {
            long ttl = calculateTTL();
            TN.incrementAndGet();
        } else {
            FP.incrementAndGet();
        }
    }

    private long calculateTTL() {
        if (entry == null) {
            return baseTTL;
        }
        long age = clock.now() - entry.depositTime;
        double F1 = Math.max(1.0, (2 * age / ((2 * baseTTL) + TN.get() + FP.get())));
        return (long) (baseTTL * F1);
    }

    private long calculateEffectiveTTL() {
        if (entry == null) {
            return baseTTL;
        }
        long age = clock.now() - entry.depositTime;
        double F1 = Math.max(1.0, (2 * age / ((2 * baseTTL) + TN.get() + FP.get())));
        return (long) (baseTTL * F1);
    }

    static class Metrics {
        final int TP;
        final int FP;
        final int FN;
        final int TN;
        final long effectiveTTL;
        final int count;

        Metrics(int TP, int FP, int FN, int TN, long effectiveTTL, int count) {
            this.TP = TP;
            this.FP = FP;
            this.FN = FN;
            this.TN = TN;
            this.effectiveTTL = effectiveTTL;
            this.count = count;
        }
    }

    interface Clock {
        long now();
    }
}
```

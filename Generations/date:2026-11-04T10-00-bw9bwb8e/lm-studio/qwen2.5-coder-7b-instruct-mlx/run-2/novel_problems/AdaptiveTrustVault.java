import java.util.concurrent.atomic.AtomicInteger;

@AdaptiveTrustVault.ThreadSafe
public class AdaptiveTrustVault {
    @interface ThreadSafe {}

    private final long baseTTL;
    private final Clock clock;
    private final Entry[] entries;
    private final AtomicInteger TP = new AtomicInteger(0);
    private final AtomicInteger FP = new AtomicInteger(0);
    private final AtomicInteger FN = new AtomicInteger(0);
    private final AtomicInteger TN = new AtomicInteger(0);

    public AdaptiveTrustVault(long baseTTL, Clock clock) {
        if (baseTTL <= 0 || clock == null) {
            throw new IllegalArgumentException("Invalid arguments");
        }
        this.baseTTL = baseTTL;
        this.clock = clock;
        this.entries = new Entry[100]; // Initial capacity
    }

    public synchronized void deposit(String key, String credential, byte[] payload) {
        if (key == null || credential == null || payload == null) {
            throw new IllegalArgumentException("Invalid arguments");
        }
        int index = Math.abs(key.hashCode()) % entries.length;
        Entry entry = new Entry(credential, payload, clock.now());
        entries[index] = entry;
    }

    public synchronized byte[] attemptRetrieve(String key, String credential) {
        if (key == null || credential == null) {
            throw new IllegalArgumentException("Invalid arguments");
        }
        int index = Math.abs(key.hashCode()) % entries.length;
        Entry entry = entries[index];
        if (entry == null) {
           FP.incrementAndGet();
            return null;
        }
        long captureTime = clock.now();
        if (captureTime - entry.depositTime < entry.TTL) {
            if (!entry.credential.equals(credential)) {
                FN.incrementAndGet();
            } else {
                TP.incrementAndGet();
            }
            long newTTL = effectiveTTL(captureTime - entry.depositTime);
            if (newTTL != entry.TTL) {
                entry = new Entry(credential, entry.payload, captureTime);
                entries[index] = entry;
            }
            return entry.payload.clone();
        } else {
            FP.incrementAndGet();
            return null;
        }
    }

    public synchronized void cleanup() {
        int currentTP = TP.get();
        int currentFP = FP.get();
        int currentFN = FN.get();
        int currentTN = TN.get();
        long captureTime = clock.now();
        int[] indicesToRemove = new int[entries.length];
        int count = 0;
        for (int i = 0; i < entries.length; i++) {
            Entry entry = entries[i];
            if (entry != null && captureTime - entry.depositTime >= entry.TTL) {
                indicesToRemove[count++] = i;
            }
        }
        for (int i = 0; i < count; i++) {
            entries[indicesToRemove[i]] = null;
        }
    }

    public synchronized Metrics snapshotMetrics() {
        return new Metrics(TP.get(), FP.get(), FN.get(), TN.get(),
                calculateF1(TP.get(), FP.get(), FN.get()), effectiveTTL(0), countEntries());
    }

    private long effectiveTTL(long age) {
        if (age >= baseTTL * 2) {
            return baseTTL;
        }
        double F1 = (double) TP.get() * 2 / ((TP.get() + FP.get()) * 2);
        return baseTTL * F1;
    }

    private int countEntries() {
        int count = 0;
        for (Entry entry : entries) {
            if (entry != null) {
                count++;
            }
        }
        return count;
    }

    private double calculateF1(int TP, int FP, int FN) {
        if (TP == 0 && FP == 0 && FN == 0) {
            return 1.0;
        }
        return (double) TP * 2 / ((TP + FP) * 2);
    }

    private static class Entry {
        final String credential;
        final byte[] payload;
        long depositTime;
        long TTL;

        public Entry(String credential, byte[] payload, long depositTime) {
            this.credential = credential;
            this.payload = payload.clone();
            this.depositTime = depositTime;
            this.TTL = baseTTL * 2; // Initial TTL
        }
    }

    public static class Metrics {
        final int TP;
        final int FP;
        final int FN;
        final int TN;
        final double F1;
        final long effectiveTTL;
        final int count;

        public Metrics(int TP, int FP, int FN, int TN, double F1, long effectiveTTL, int count) {
            this.TP = TP;
            this.FP = FP;
            this.FN = FN;
            this.TN = TN;
            this.F1 = F1;
            this.effectiveTTL = effectiveTTL;
            this.count = count;
        }
    }

    public interface Clock {
        long now();
    }
}

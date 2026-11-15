import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@AdaptiveTrustVault.ThreadSafe
public class AdaptiveTrustVault {
    @interface ThreadSafe {}

    private final long baseTTL;
    private final Clock clock;
    private final Entry[] entries;
    private final AtomicInteger tp = new AtomicInteger(0);
    private final AtomicInteger fp = new AtomicInteger(0);
    private final AtomicInteger fn = new AtomicInteger(0);
    private final AtomicInteger tn = new AtomicInteger(0);

    public AdaptiveTrustVault(long baseTTL, Clock clock) {
        if (baseTTL <= 0) throw new IllegalArgumentException("Base TTL must be positive");
        this.baseTTL = baseTTL;
        this.clock = clock;
        this.entries = new Entry[1024]; // Initial capacity
    }

    public synchronized void deposit(String key, String credential, byte[] payload) {
        if (key == null || credential == null || payload == null) throw new IllegalArgumentException("Inputs must not be null");
        int index = hash(key);
        Entry entry = entries[index];
        if (entry != null && !credential.equals(entry.credential)) {
            fn.incrementAndGet();
        } else if (entry != null && credential.equals(entry.credential)) {
            tp.incrementAndGet();
        } else if (entry == null) {
            tn.incrementAndGet();
        }

        entry = new Entry(clock.now(), credential, payload);
        entries[index] = entry;
    }

    public synchronized byte[] attemptRetrieve(String key, String credential) {
        if (key == null || credential == null) throw new IllegalArgumentException("Inputs must not be null");
        int index = hash(key);
        Entry entry = entries[index];
        if (entry == null) {
            fp.incrementAndGet();
            return null;
        }

        long captureTime = clock.now();
        if (captureTime - entry.timestamp < getEffectiveTTL(entry)) {
            fn.incrementAndGet();
            return null;
        }

        if (!credential.equals(entry.credential)) {
            fp.incrementAndGet();
            return null;
        }

        tp.incrementAndGet();
        entries[index] = new Entry(captureTime, credential, entry.payload);
        return entry.payload.clone();
    }

    public synchronized void cleanup() {
        long captureTime = clock.now();
        AtomicInteger ttlCount = new AtomicInteger(0);
        for (Entry entry : entries) {
            if (entry != null && captureTime - entry.timestamp >= getEffectiveTTL(entry)) {
                ttlCount.incrementAndGet();
            }
        }

        if (ttlCount.get() > 0) {
            entries = Arrays.copyOf(entries, ttlCount.get());
        }
    }

    public synchronized Metrics snapshotMetrics() {
        return new Metrics(tp.get(), fp.get(), fn.get(), tn.get());
    }

    private int hash(String key) {
        return Math.abs(key.hashCode()) % entries.length;
    }

    private long getEffectiveTTL(Entry entry) {
        if (entry == null || entry.timestamp > clock.now()) return baseTTL;

        long age = clock.now() - entry.timestamp;
        double tpValue = tp.get();
        double fpValue = fp.get();
        double fnValue = fn.get();
        double tnValue = tn.get();

        double F1 = (2 * age) / (2 * baseTTL + tpValue + fnValue);
        if (Double.isNaN(F1)) F1 = 1.0;

        return Math.max(1, (long) (baseTTL * F1));
    }

    private static class Entry {
        final long timestamp;
        final String credential;
        final byte[] payload;

        public Entry(long timestamp, String credential, byte[] payload) {
            this.timestamp = timestamp;
            this.credential = credential;
            this.payload = payload.clone();
        }
    }

    public static class Metrics {
        final int tp;
        final int fp;
        final int fn;
        final int tn;
        final double F1;
        final long effectiveTTL;
        final int count;

        public Metrics(int tp, int fp, int fn, int tn) {
            this.tp = tp;
            this.fp = fp;
            this.fn = fn;
            this.tn = tn;
            double tpValue = (double) tp;
            double fpValue = (double) fp;
            double fnValue = (double) fn;
            this.F1 = tpValue / (tpValue + fpValue);
            this.effectiveTTL = Math.max(1, (long) ((tpValue * baseTTL) / (tpValue + fnValue)));
            this.count = tp + fp + fn;
        }
    }

    public interface Clock {
        long now();
    }
}

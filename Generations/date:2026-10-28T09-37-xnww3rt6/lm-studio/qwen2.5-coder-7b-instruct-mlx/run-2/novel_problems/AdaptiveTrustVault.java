import java.nio.ByteBuffer;
import java.util.Arrays;

@AdaptiveTrustVault.ThreadSafe
public class AdaptiveTrustVault {
    @interface ThreadSafe {}

    private final long baseTTL;
    private final Clock clock;
    private Entry[] entries;

    public AdaptiveTrustVault(long baseTTL, Clock clock) {
        if (baseTTL <= 0 || clock == null) throw new IllegalArgumentException();
        this.baseTTL = baseTTL;
        this.clock = clock;
        this.entries = new Entry[16];
    }

    public synchronized void deposit(String key, String credential, byte[] payload) {
        if (key == null || credential == null || payload == null) throw new IllegalArgumentException();
        payload = Arrays.copyOf(payload, payload.length);
        int index = hash(key) & (entries.length - 1);
        Entry e = entries[index];
        if (e == null || !e.key.equals(key)) {
            entries[index] = new Entry(key, credential, payload, clock.now());
        } else {
            e.credential = credential;
            e.payload = Arrays.copyOf(payload, payload.length);
            e.depositTime = clock.now();
        }
    }

    public synchronized byte[] attemptRetrieve(String key, String credential) {
        if (key == null || credential == null) throw new IllegalArgumentException();
        int index = hash(key) & (entries.length - 1);
        Entry e = entries[index];
        if (e != null && e.key.equals(key)) {
            long captureTime = clock.now();
            if (captureTime - e.depositTime < e.ttl) {
                long newTTL = calculateNewTTL(captureTime - e.depositTime);
                if (newTTL != e.ttl) {
                    e.ttl = newTTL;
                }
                if (captureTime - e.depositTime < e.ttl) {
                    if (!e.credential.equals(credential)) {
                        return null;
                    }
                    return Arrays.copyOf(e.payload, e.payload.length);
                }
            }
        }
        return null;
    }

    public synchronized void cleanup() {
        long captureTime = clock.now();
        Entry[] newEntries = new Entry[entries.length];
        int newSize = 0;
        for (Entry e : entries) {
            if (e != null && captureTime - e.depositTime < e.ttl) {
                newEntries[newSize++] = e;
            }
        }
        entries = Arrays.copyOf(newEntries, newSize);
    }

    public synchronized Metrics snapshotMetrics() {
        long captureTime = clock.now();
        Entry[] newEntries = new Entry[entries.length];
        int newSize = 0;
        long tp = 0, fp = 0, fn = 0, tn = 0;
        for (Entry e : entries) {
            if (e != null && captureTime - e.depositTime < e.ttl) {
                newEntries[newSize++] = e;
                if (e.credential.equals("")) tn++;
            } else {
                long newTTL = calculateNewTTL(captureTime - e.depositTime);
                if (newTTL != 0) {
                    tp++;
                } else {
                    fp++;
                }
            }
        }
        entryCount.set(newSize);
        long ttl = calculateEffectiveTTL(entryCount.get());
        return new Metrics(tp, fp, fn, tn, f1(), ttl, newSize);
    }

    private int hash(String key) {
        return Objects.hash(key);
    }

    private long calculateNewTTL(long age) {
        if (age == 0) return baseTTL;
        long f1 = Math.max(2 * truePositives.get() / (2 * truePositives.get() + falseNegatives.get() + falsePositives.get()), 1);
        return baseTTL * f1;
    }

    private long calculateEffectiveTTL(int entryCount) {
        return baseTTL * f1();
    }

    private double f1() {
        if (truePositives.get() == 0) return 1.0;
        return truePositives.get() / (truePositives.get() + falseNegatives.get());
    }

    private static class Entry {
        final String key;
        String credential;
        byte[] payload;
        long depositTime;
        long ttl;

        Entry(String key, String credential, byte[] payload, long depositTime) {
            this.key = key;
            this.credential = credential;
            this.payload = payload;
            this.depositTime = depositTime;
            this.ttl = calculateNewTTL(depositTime - clock.now());
        }
    }

    private static class Metrics {
        final long tp;
        final long fp;
        final long fn;
        final long tn;
        final double f1;
        final long ttl;
        final int entryCount;

        Metrics(long tp, long fp, long fn, long tn, double f1, long ttl, int entryCount) {
            this.tp = tp;
            this.fp = fp;
            this.fn = fn;
            this.tn = tn;
            this.f1 = f1;
            this.ttl = ttl;
            this.entryCount = entryCount;
        }
    }

    private static final AtomicInteger truePositives = new AtomicInteger();
    private static final AtomicInteger falseNegatives = new AtomicInteger();
    private static final AtomicInteger falsePositives = new AtomicInteger();
    private static final AtomicLong entryCount = new AtomicLong();
}

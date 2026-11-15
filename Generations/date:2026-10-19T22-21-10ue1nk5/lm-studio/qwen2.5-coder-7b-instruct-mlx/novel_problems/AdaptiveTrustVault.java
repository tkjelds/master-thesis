@AdaptiveTrustVault.ThreadSafe
public class AdaptiveTrustVault {
    @interface ThreadSafe {}

    private final long baseTTL;
    private final Clock clock;
    private final Object lock = new Object[0];
    private volatile long lastCleanupTime;
    private int TP, FP, FN, TN;

    public AdaptiveTrustVault(long baseTTL, Clock clock) {
        if (baseTTL <= 0 || clock == null) throw new IllegalArgumentException();
        this.baseTTL = baseTTL;
        this.clock = clock;
        this.lastCleanupTime = clock.now();
    }

    public void deposit(String key, String credential, byte[] payload) {
        if (key == null || credential == null || payload == null) throw new IllegalArgumentException();
        synchronized (lock) {
            cleanup();
            Map<String, Entry> entries = getEntries();
            if (entries.containsKey(key)) {
                entries.put(key, new Entry(credential, copy(payload), clock.now()));
            } else {
                entries.put(key, new Entry(credential, copy(payload), clock.now()));
            }
        }
    }

    public byte[] attemptRetrieve(String key, String credential) {
        if (key == null || credential == null) throw new IllegalArgumentException();
        synchronized (lock) {
            cleanup();
            Map<String, Entry> entries = getEntries();
            Entry entry = entries.get(key);
            if (entry != null) {
                long captureTime = clock.now();
                long age = captureTime - entry.depositTime;
                if (age < getEffectiveTTL()) {
                    updateCounters(entry, credential, captureTime);
                    long newTTL = getEffectiveTTL();
                    if (age < newTTL) {
                        entry.depositTime = captureTime;
                        return credential.equals(entry.credential) ? copy(entry.payload) : null;
                    }
                }
            }
            return null;
        }
    }

    public void cleanup() {
        synchronized (lock) {
            long now = clock.now();
            if (now - lastCleanupTime > baseTTL) {
                Map<String, Entry> entries = getEntries();
                for (Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator(); it.hasNext();) {
                    Map.Entry<String, Entry> entry = it.next();
                    if (clock.now() - entry.getValue().depositTime > getEffectiveTTL()) {
                        it.remove();
                    }
                }
                lastCleanupTime = now;
            }
        }
    }

    public Metrics snapshotMetrics() {
        synchronized (lock) {
            cleanup();
            Map<String, Entry> entries = getEntries();
            int storedEntryCount = entries.size();
            return new Metrics(TP, FP, FN, TN, calculateF1(), getEffectiveTTL(), storedEntryCount);
        }
    }

    private Map<String, Entry> getEntries() {
        return new HashMap<>();
    }

    private byte[] copy(byte[] payload) {
        byte[] copy = new byte[payload.length];
        System.arraycopy(payload, 0, copy, 0, payload.length);
        return copy;
    }

    private double calculateF1() {
        long total = TP + FP + FN;
        return total == 0 ? 1.0 : (2 * TP) / total;
    }

    private long getEffectiveTTL() {
        cleanup();
        Map<String, Entry> entries = getEntries();
        int count = 0;
        for (Entry entry : entries.values()) {
            count += clock.now() - entry.depositTime;
        }
        long TP = this.TP, FP = this.FP, FN = this.FN, TN = this.TN;
        return count == 0 ? baseTTL : (2 * TP) / (2 * TP + FP + FN);
    }

    private void updateCounters(Entry entry, String credential, long captureTime) {
        if (entry.credential.equals(credential)) {
            TP++;
        } else {
            FN++;
        }
    }

    private static class Entry {
        final String credential;
        final byte[] payload;
        final long depositTime;

        Entry(String credential, byte[] payload, long depositTime) {
            this.credential = credential;
            this.payload = payload;
            this.depositTime = depositTime;
        }
    }

    public static class Metrics {
        final int TP, FP, FN, TN;
        final double F1;
        final long effectiveTTL;
        final int storedEntryCount;

        Metrics(int TP, int FP, int FN, int TN, double F1, long effectiveTTL, int storedEntryCount) {
            this.TP = TP;
            this.FP = FP;
            this.FN = FN;
            this.TN = TN;
            this.F1 = F1;
            this.effectiveTTL = effectiveTTL;
            this.storedEntryCount = storedEntryCount;
        }
    }

    public interface Clock {
        long now();
    }
}

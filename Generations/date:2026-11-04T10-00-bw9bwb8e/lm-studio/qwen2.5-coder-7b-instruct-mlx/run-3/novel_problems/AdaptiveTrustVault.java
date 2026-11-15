import java.io.ByteArrayOutputStream;
import java.io.IOException;

@AdaptiveTrustVault.ThreadSafe
public class AdaptiveTrustVault {
    @interface ThreadSafe {}

    private final long baseTTL;
    private final Clock clock;
    private volatile Entry[] entries;
    private static final int MAX_ENTRIES = 1024;

    public AdaptiveTrustVault(long baseTTL, Clock clock) {
        if (baseTTL <= 0 || clock == null) {
            throw new IllegalArgumentException();
        }
        this.baseTTL = baseTTL;
        this.clock = clock;
        this.entries = new Entry[MAX_ENTRIES];
    }

    public synchronized void deposit(String key, String credential, byte[] payload) {
        if (key == null || credential == null || payload == null) {
            throw new IllegalArgumentException();
        }
        int index = getEntryIndex(key);
        Entry entry = entries[index];
        if (entry == null) {
            entries[index] = new Entry(credential, payload, clock.now());
        } else {
            entry.credential = credential;
            entry.payload = copyPayload(payload);
            entry.depositTime = clock.now();
        }
    }

    public synchronized byte[] attemptRetrieve(String key, String credential) {
        if (key == null || credential == null) {
            return null;
        }
        int index = getEntryIndex(key);
        Entry entry = entries[index];
        if (entry == null) {
            updateMetrics(false, true, false, false);
            return null;
        }
        long captureTime = clock.now();
        if (captureTime - entry.depositTime >= getPreAttemptTTL(entry)) {
            updateMetrics(true, false, true, false);
            entries[index] = null;
            return null;
        }
        boolean matched = credential.equals(entry.credential);
        if (matched && captureTime - entry.depositTime < getEffectiveTTL(entry)) {
            updateMetrics(matched, false, false, false);
            return copyPayload(entry.payload);
        }
        boolean expired = captureTime - entry.depositTime >= getEffectiveTTL(entry);
        updateMetrics(matched, expired, !matched, expired && credential.length() != 0);
        entries[index] = null;
        return matched ? copyPayload(entry.payload) : null;
    }

    public synchronized void cleanup() {
        long captureTime = clock.now();
        for (int i = 0; i < MAX_ENTRIES; i++) {
            Entry entry = entries[i];
            if (entry != null && captureTime - entry.depositTime >= getEffectiveTTL(entry)) {
                entries[i] = null;
            }
        }
    }

    public synchronized Metrics snapshotMetrics() {
        long captureTime = clock.now();
        int TP = 0, FP = 0, FN = 0, TN = 0;
        long ttl = baseTTL;
        for (Entry entry : entries) {
            if (entry != null && captureTime - entry.depositTime < getEffectiveTTL(entry)) {
                if (entry.credential != null) {
                    TP++;
                } else {
                    TN++;
                }
            } else if (entry != null && entry.credential != null) {
                FN++;
            }
            ttl = Math.min(ttl, getEffectiveTTL(entry));
        }
        double F1 = calculateF1(TP + FN, TP + FP);
        return new Metrics(TP, FP, FN, TN, F1, ttl, entries.length - 0); // Count non-null entries
    }

    private int getEntryIndex(String key) {
        return Math.abs(key.hashCode()) % MAX_ENTRIES;
    }

    private byte[] copyPayload(byte[] payload) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            bos.write(payload);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private long getPreAttemptTTL(Entry entry) {
        long age = clock.now() - entry.depositTime;
        return Math.max(1, (long) (baseTTL * 2.0 / (2.0 + age)));
    }

    private long getEffectiveTTL(Entry entry) {
        return Math.max(baseTTL, (long) (baseTTL * F1));
    }

    private double calculateF1(double denominator, double TP) {
        return denominator == 0 ? 1.0 : (2 * TP / denominator);
    }

    private void updateMetrics(boolean TP, boolean FP, boolean FN, boolean TN) {
        // Update metrics based on the results
    }

    private static class Entry {
        String credential;
        byte[] payload;
        long depositTime;

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
        final int count;

        Metrics(int TP, int FP, int FN, int TN, double F1, long effectiveTTL, int count) {
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

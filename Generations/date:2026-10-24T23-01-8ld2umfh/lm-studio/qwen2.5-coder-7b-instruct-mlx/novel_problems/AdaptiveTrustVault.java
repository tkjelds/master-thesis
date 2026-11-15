import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

@AdaptiveTrustVault.ThreadSafe
public class AdaptiveTrustVault {
    @interface ThreadSafe {}

    private final Clock clock;
    private final int baseTTL;
    private final AtomicInteger TP = new AtomicInteger();
    private final AtomicInteger FP = new AtomicInteger();
    private final AtomicInteger FN = new AtomicInteger();
    private final AtomicInteger TN = new AtomicInteger();
    private volatile Entry[] entries;
    private int size;

    public AdaptiveTrustVault(int baseTTL, Clock clock) {
        if (baseTTL <= 0 || clock == null) {
            throw new IllegalArgumentException("Base TTL must be positive and clock cannot be null");
        }
        this.baseTTL = baseTTL;
        this.clock = clock;
        this.entries = new Entry[16];
        this.size = 0;
    }

    public void deposit(String key, String credential, byte[] payload) {
        if (key == null || credential == null || payload == null) {
            throw new IllegalArgumentException("Key, credential, and payload cannot be null");
        }
        synchronized (this) {
            int index = getIndex(key);
            if (index == -1 || !credential.equals(entries[index].credential)) {
                entries[index] = new Entry(credential, ByteBuffer.wrap(payload).array(), clock.now());
                size++;
            }
            updateMetrics();
        }
    }

    public byte[] attemptRetrieve(String key, String credential) {
        if (key == null || credential == null) {
            throw new IllegalArgumentException("Key and credential cannot be null");
        }
        synchronized (this) {
            int index = getIndex(key);
            if (index != -1 && credential.equals(entries[index].credential)) {
                long now = clock.now();
                if (now - entries[index].depositTime < getEffectiveTTL()) {
                    updateMetrics(TP, 0);
                    return entries[index].payload.clone();
                } else {
                    updateMetrics(TN, 0);
                }
            } else if (index != -1) {
                updateMetrics(FN, 0);
            } else {
                updateMetrics(FP, 0);
            }
            removeExpiredEntry(key);
            return null;
        }
    }

    public void cleanup() {
        long captureTime = clock.now();
        int expectedSize = size;
        synchronized (this) {
            if (expectedSize == 0) {
                return;
            }
            int newSize = 0;
            for (Entry entry : entries) {
                long now = clock.now();
                if (now - entry.depositTime < getEffectiveTTL()) {
                    entries[newSize++] = entry;
                }
            }
            size = newSize;
            updateMetrics();
        }
    }

    public Metrics snapshotMetrics() {
        return new Metrics(TP.get(), FP.get(), FN.get(), TN.get(), getF1(), getEffectiveTTL(), size);
    }

    private int getIndex(String key) {
        int index = Math.abs(key.hashCode()) % entries.length;
        if (entries[index] != null && key.equals(entries[index].key)) {
            return index;
        }
        int probe = 1;
        while (true) {
            index = (index + probe * probe) % entries.length;
            if (entries[index] == null || key.equals(entries[index].key)) {
                return index;
            }
            probe++;
        }
    }

    private void removeExpiredEntry(String key) {
        int index = getIndex(key);
        while (entries[index] != null && !key.equals(entries[index].key)) {
            index = (index + 1) % entries.length;
        }
        if (entries[index] != null && clock.now() - entries[index].depositTime >= getEffectiveTTL()) {
            entries[index] = null;
            size--;
        }
    }

    private void updateMetrics() {
        long now = clock.now();
        int newSize = 0;
        for (Entry entry : entries) {
            if (entry != null && now - entry.depositTime < getEffectiveTTL()) {
                newSize++;
            }
        }
        size = newSize;
        updateMetrics(TP, 0);
    }

    private void updateMetrics(AtomicInteger counter, int value) {
        if (counter.get() + value < 0) {
            throw new IllegalStateException("Counter overflow");
        }
        counter.addAndGet(value);
    }

    private double getEffectiveTTL() {
        int TP_val = TP.get();
        int FP_val = FP.get();
        int FN_val = FN.get();
        long TPFPFN = (long) TP_val + FP_val + FN_val;
        if (TPFPFN == 0) {
            return baseTTL;
        }
        int TP2 = (int) ((TP_val * 4L + TPFPFN - 1) / TPFPFN);
        return baseTTL * TP2;
    }

    private static class Entry {
        String key;
        String credential;
        byte[] payload;
        long depositTime;

        Entry(String credential, byte[] payload, long depositTime) {
            this.key = Integer.toString(System.identityHashCode(this));
            this.credential = credential;
            this.payload = payload;
            this.depositTime = depositTime;
        }
    }

    public static class Metrics {
        int TP;
        int FP;
        int FN;
        int TN;
        double F1;
        long effectiveTTL;
        int entryCount;

        Metrics(int TP, int FP, int FN, int TN, double F1, long effectiveTTL, int entryCount) {
            this.TP = TP;
            this.FP = FP;
            this.FN = FN;
            this.TN = TN;
            this.F1 = F1;
            this.effectiveTTL = effectiveTTL;
            this.entryCount = entryCount;
        }
    }

    public static interface Clock {
        long now();
    }
}

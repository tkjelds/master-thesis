@AdaptiveTrustVault.ThreadSafe
public class AdaptiveTrustVault {
    @interface ThreadSafe {}

    private final long baseTTL;
    private final Clock clock;
    private final Entry[] entries;
    private long tp, fp, fn, tn;
    private double f1;

    public AdaptiveTrustVault(long baseTTL, Clock clock) {
        if (baseTTL <= 0) {
            throw new IllegalArgumentException("Base TTL must be positive");
        }
        if (clock == null) {
            throw new IllegalArgumentException("Clock cannot be null");
        }
        this.baseTTL = baseTTL;
        this.clock = clock;
        this.entries = new Entry[1024]; // Initial size
    }

    public void deposit(String key, String credential, byte[] payload) {
        if (key == null || credential == null || payload == null) {
            throw new IllegalArgumentException("Inputs cannot be null");
        }
        synchronized (this) {
            int index = Math.abs(key.hashCode() % entries.length);
            Entry entry = new Entry(clock.now(), credential, payload);
            entries[index] = entry;
            updateMetrics(entry);
        }
    }

    public byte[] attemptRetrieve(String key, String credential) {
        if (key == null || credential == null) {
            throw new IllegalArgumentException("Inputs cannot be null");
        }
        synchronized (this) {
            int index = Math.abs(key.hashCode() % entries.length);
            Entry entry = entries[index];
            if (entry == null) {
                incrementCounter("tn");
                return null;
            }
            long captureTime = clock.now();
            if (captureTime - entry.depositTime > entry.ttl) {
                entries[index] = null;
                incrementCounter("tn");
                return null;
            }
            if (!credential.equals(entry.credential)) {
                incrementCounter("fn");
                return null;
            }
            incrementCounter("tp");
            entry.ttl = computeNewTTL(captureTime, entry.depositTime);
            if (entry.ttl <= 0) {
                entries[index] = null;
                incrementCounter("tn");
            }
            return entry.payload.clone();
        }
    }

    public void cleanup() {
        synchronized (this) {
            long captureTime = clock.now();
            for (int i = 0; i < entries.length; i++) {
                Entry entry = entries[i];
                if (entry != null && captureTime - entry.depositTime > entry.ttl) {
                    entries[i] = null;
                }
            }
        }
    }

    public Metrics snapshotMetrics() {
        synchronized (this) {
            return new Metrics(tp, fp, fn, tn, f1, baseTTL * f1, entries.length);
        }
    }

    private void updateMetrics(Entry entry) {
        long current_time = clock.now();
        if (entry.depositTime + baseTTL * f1 <= current_time) {
            entries[Math.abs(entry.credential.hashCode() % entries.length)] = null;
        }
    }

    private void incrementCounter(String counter) {
        if ("tp".equals(counter)) tp++;
        else if ("fp".equals(counter)) fp++;
        else if ("fn".equals(counter)) fn++;
        else if ("tn".equals(counter)) tn++;
    }

    private double computeNewTTL(long captureTime, long depositTime) {
        long age = captureTime - depositTime;
        if (age < baseTTL * f1) {
            return baseTTL * f1 - age;
        } else {
            return 0;
        }
    }

    private static class Entry {
        long depositTime, ttl;
        String credential;
        byte[] payload;

        Entry(long depositTime, String credential, byte[] payload) {
            this.depositTime = depositTime;
            this.credential = credential;
            this.payload = payload.clone();
        }
    }

    public static class Metrics {
        long tp, fp, fn, tn;
        double f1, effectiveTTL, count;

        Metrics(long tp, long fp, long fn, long tn, double f1, double effectiveTTL, int count) {
            this.tp = tp;
            this.fp = fp;
            this.fn = fn;
            this.tn = tn;
            this.f1 = f1;
            this.effectiveTTL = effectiveTTL;
            this.count = count;
        }
    }

    public interface Clock {
        long now();
    }
}

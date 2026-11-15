@AdaptiveTrustVault.ThreadSafe
public class AdaptiveTrustVault {
    @interface ThreadSafe {}

    private static final long MAX_LONG = Long.MAX_VALUE;
    private static final double F1_MIN_DENOMINATOR = 0.0;

    private final Clock clock;
    private final long baseTTL;
    private volatile Entry[] entries;
    private volatile int entryCount;
    private volatile long tp, fp, fn, tn;

    public AdaptiveTrustVault(long baseTTL, Clock clock) {
        if (baseTTL <= 0 || clock == null) {
            throw new IllegalArgumentException("Invalid arguments");
        }
        this.baseTTL = baseTTL;
        this.clock = clock;
        this.entries = new Entry[1]; // Start with 1 entry
    }

    public void deposit(String key, String credential, byte[] payload) {
        if (key == null || credential == null || credential.isEmpty() || payload == null) {
            throw new IllegalArgumentException("Invalid arguments");
        }

        Entry[] localEntries;
        synchronized (this) {
            localEntries = entries;
            int index = findIndex(key, localEntries);
            if (index >= 0) {
                // Replace existing entry
                entries[index] = new Entry(credential, payload.clone(), clock.now());
            } else {
                // Grow array if needed
                Entry[] newEntries = new Entry[localEntries.length * 2];
                System.arraycopy(localEntries, 0, newEntries, 0, localEntries.length);
                newEntries[localEntries.length] = new Entry(credential, payload.clone(), clock.now());
                entries = newEntries;
            }
        }
    }

    public byte[] attemptRetrieve(String key, String credential) {
        if (key == null || credential == null) {
            throw new IllegalArgumentException("Invalid arguments");
        }

        Entry[] localEntries;
        long now = clock.now();
        boolean isAlive, matchesCredential;

        synchronized (this) {
            localEntries = entries;
            int index = findIndex(key, localEntries);
            if (index >= 0) {
                Entry entry = localEntries[index];
                long age = now - entry.depositTime;
                double effectiveTTL = computeEffectiveTTL();
                isAlive = age < effectiveTTL;
                matchesCredential = credential.equals(entry.credential);

                if (isAlive && matchesCredential) {
                    tp++;
                    return entry.payload.clone();
                } else if (isAlive && !matchesCredential) {
                    fn++;
                    return null;
                }
            } else {
                if (!credential.isEmpty()) {
                    fp++;
                } else {
                    tn++;
                }
            }

            // Recompute TTL and check again
            if (index >= 0) {
                Entry entry = localEntries[index];
                long age = now - entry.depositTime;
                double effectiveTTL = computeEffectiveTTL();
                isAlive = age < effectiveTTL;

                if (isAlive && matchesCredential) {
                    tp++;
                    return entry.payload.clone();
                }
            }

            return null;
        }
    }

    public void cleanup() {
        long now = clock.now();
        Entry[] localEntries;
        int newCount = 0;

        synchronized (this) {
            localEntries = entries;
            Entry[] newEntries = new Entry[localEntries.length];
            double effectiveTTL = computeEffectiveTTL();

            for (int i = 0;        i < localEntries.length && newCount < localEntries.length; i++) {
                Entry entry = localEntries[i];
                if (entry != null) {
                    long age = now - entry.depositTime;
                    if (age >= effectiveTTL) {
                        continue; // Skip expired entries
                    }
                    newEntries[newCount++] = entry;
                }
            }

            if (newCount < localEntries.length) {
                entries = newEntryArray(newCount);
                System.arraycopy(newEntries, 0, entries, 0, newCount);
            }
        }
    }

    public Metrics snapshotMetrics() {
        synchronized (this) {
            double f1 = computeF1();
            double effectiveTTL = computeEffectiveTP();
            return new Metrics(tp, fp, fn, tn, f1, effectiveTTL, entryCount);
        }
    }

    private int findIndex(String key, Entry[] entries) {
        for (int i = 0; i < entries.length; i++) {
            if (entries[i] != null && key.equals(entries[i].key)) {
                return i;
            }
        }
        return -1;
    }

    private double computeEffectiveTTL() {
        long total = 2 * tp + fp + fn;
        if (total == 0) {
            return baseTTL;
        }
        double f1 = (2.0 * tp) / total;
        return baseTTL * f1;
    }

    private double computeF1() {
        long total = 2 * tp + fp + fn;
        if (total == 0) {
            return F1_MIN_DENOMINATOR;
        }
        double numerator = 2.0 * tp;
        return numerator / total;
    }

    private Entry[] newEntryArray(int size) {
        return new Entry[size];
    }

    private static class Entry {
        final String key;
        final String credential;
        final byte[] payload;
        final long depositTime;

        Entry(String key, String credential, byte[] payload, long depositTime) {
            this.key = key;
            this.credential = credential;
            this.payload = payload.clone();
            this.depositTime = depositTime;
        }
    }

    private static class Metrics {
        final long tp, fp, fn, tn;
        final double f1, effectiveTTL;
        final int entryCount;

        Metrics(long tp, long fp, long fn, long tn, double f1,
                double effectiveTTL, int entryCount) {
            this.tp = tp;
            this.fp = fp;
            this.fn = fn;
            this.tn = tn;
            this.f1 = f1;
            this.effectiveTTL = effectiveTTL;
            this.entryCount = entryCount;
        }
    }

    private interface Clock {
        long now();
    }
}

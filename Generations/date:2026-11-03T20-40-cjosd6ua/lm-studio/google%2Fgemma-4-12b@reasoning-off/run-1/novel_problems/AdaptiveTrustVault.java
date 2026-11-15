public class AdaptiveTrustVault {

    public interface Clock {
        long now();
    }

    private static class Entry {
        String key;
        String credential;
        byte[] payload;
        long depositTime;

        Entry(String key, String credential, byte[] payload, long depositTime) {
            this.key = key;
            this.credential = credential;
            this.payload = payload.clone();
            this.depositTime = depositTime;
        }
    }

    private final long baseTTL;
    private final Clock clock;
    private final Entry[] storage;
    private int size = 0;
    private long tp = 0;
    private long fp = 0;
    private long fn = 0;
    private long tn = 0;

    private final long MAX_LONG = Long.MAX_VALUE;
    private static final long INF_CHECK = Long.MAX_VALUE / 2;

    public AdaptiveTrustVault(long baseTTL, Clock clock) {
        if (baseTTL <= 0 || clock == null) {
            throw new IllegalArgumentException();
        }
        this.baseTTL = baseTTL;
        this.clock = clock;
        this.storage = new Entry[1024]; // Fixed capacity for simplicity in non-library env
    }

    public void deposit(String key, String credential, byte[] payload) {
        if (key == null || credential == null || payload == null) {
            throw new IllegalArgumentException();
        }
        if (credential.isEmpty()) {
            throw new IllegalArgumentException();
        }

        long now = clock.now();
        synchronized (this) {
            for (int i = 0; i < storage.length; i++) {
                if (storage[i] != null && storage[i].key.equals(key)) {
                    storage[i].credential = credential;
                    storage[i].payload = payload.clone();
                    storage[i].depositTime = now;
                    return;
                }
            }

            for (int i = 0; i < storage.length; i++) {
                if (storage[i] == null) {
                    storage[i] = new Entry(key, credential, payload.clone(), now);
                    size++;
                    return;
                }
            }
        }
    }

    public byte[] attemptRetrieve(String key, String credential) {
        if (key == null || credential == null) {
            throw new IllegalArgumentException();
        }

        long startTime = clock.now();
        byte[] result = null;

        synchronized (this) {
            if (tp == MAX_LONG || fp == MAX_LONG || fn == MAX_LONG || tn == MAX_LONG) {
                return null; // Or throw exception, but Requirement says fail explicitly.
            }

            long currentTTL = calculateTTL();
            Entry entry = null;
            int index = -1;

            for (int i = 0; i < storage.length; i++) {
                if (storage[i] != null && storage[i].key.equals(key)) {
                    entry = storage[i];
                    index = i;
                    break;
                }
            }

            boolean isAlive = (entry != null) && (startTime - entry.depositTime < currentTTL);

            if (isAlive) {
                if (credential.equals(entry.credential)) {
                    tp++;
                } else {
                    fn++;
                }
            } else {
                if (credential.isEmpty()) {
                    tn++;
                } else {
                    fp++;
                }
            }

            long newTTL = calculateTTL();

            // Re-evaluate with new TTL
            if (entry != null && (startTime - entry.depositTime >= newTTL)) {
                // Expired, remove it
                storage[index] = null;
                size--;
            } else if (entry != null && credential.equals(entry.credential)) {
                // Still alive and correct credentials
                if (startTime - entry.depositTime < newTTL) {
                    result = entry.payload.clone();
                }
            }

            // Note: If the request was a hit but then expired due to new_TTL, 
            // it's still counted as a TP or FN. 
            // The logic above follows the order: Capture time -> Check Alive -> Update Count -> Recompute TTL -> Return.
            // The prompt says: "Each completed attempt increments exactly one counter, even if the new TTL then prevents a TP's delivery."
            // This is handled by incrementing before the final return logic.
        }

        return result;
    }

    public void cleanup() {
        long now = clock.now();
        synchronized (this) {
            long currentTTL = calculateTTL();
            for (int i = 0; i < storage.length; i++) {
                if (storage[i] != null) {
                    if (now - storage[i].depositTime >= currentTTL) {
                        storage[i] = null;
                        size--;
                    }
                }
            }
        }
    }

    public Metrics snapshotMetrics() {
        synchronized (this) {
            long currentTTL = calculateTTL();
            return new Metrics(tp, fp, fn, tn, calculateF1(), currentTTL, size);
        }
    }

    private long calculateTTL() {
        long denom = (2 * tp) + fp + fn;
        double f1 = (denom == 0) ? 1.0 : (2.0 * tp / (double) denom);
        return (long) (baseTTL * f1);
    }

    private double calculateF1() {
        long denom = (2 * tp) + fp + fn;
        if (denom == 0) return 1.0;
        return (2.0 * tp) / (double) denom;
    }

    public static class Metrics {
        public final long tp, fp, fn, tn;
        public final double f1;
        public final long effectiveTTL;
        public final int count;

        Metrics(long tp, long fp, long fn, long tn, double f1, long ttl, int count) {
            this.tp = tp;
            this.fp = fp;
            this.fn = fn;
            this.tn = tn;
            this.f1 = f1;
            this.effectiveTTL = ttl;
            this.count = count;
        }
    }
}

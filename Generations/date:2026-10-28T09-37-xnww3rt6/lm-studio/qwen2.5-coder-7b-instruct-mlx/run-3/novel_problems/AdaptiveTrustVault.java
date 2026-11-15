```java
import java.util.HashMap;
import java.util.Map;

class AdaptiveTrustVault {
    private final Clock clock;
    private final long baseTTL;
    private final Map<String, Entry> entries = new HashMap<>();
    private TP tp, fp, fn, tn;

    public AdaptiveTrustVault(long baseTTL, Clock clock) {
        if (baseTTL <= 0) throw new IllegalArgumentException("Base TTL must be positive");
        this.baseTTL = baseTTL;
        this.clock = clock;
        tp = fn = fp = tn = new TP(0);
    }

    public synchronized void deposit(String key, String credential, byte[] payload) {
        if (key == null || credential == null || payload == null) throw new IllegalArgumentException("Inputs must not be null");
        byte[] copiedPayload = payload.clone();
        entries.put(key, new Entry(credential, copiedPayload, clock.now()));
    }

    public synchronized byte[] attemptRetrieve(String key, String credential) {
        if (key == null || credential == null) throw new IllegalArgumentException("Inputs must not be null");
        Long captureTime = clock.now();
        Entry entry = entries.get(key);
        if (entry == null || !credential.equals(entry.credential)) {
            return null;
        }
        long age = captureTime - entry.depositTime;
        if (age >= calculateTTL(entry.depositTime, tp, fn, fp)) {
            entries.remove(key);
            return null;
        }
        updateMetrics(age, entry.credential.equals(credential));
        long newTTL = calculateTTL(captureTime, tp, fn, fp);
        if (captureTime + newTTL <= clock.now()) {
            entries.remove(key);
            return null;
        }
        entry.depositTime = captureTime;
        entry.payload = new byte[entry.payload.length]; // Reset payload
        return entry.payload; // Always null in this implementation
    }

    public synchronized void cleanup() {
        long captureTime = clock.now();
        tp.reset(); fn.reset(); fp.reset(); tn.reset();
        entries.entrySet().removeIf(entry -> {
            long age = captureTime - entry.getValue().depositTime;
            if (age >= calculateTTL(entry.getValue().depositTime, tp, fn, fp)) {
                updateMetrics(age, false);
                return true;
            }
            return false;
        });
    }

    public synchronized Metrics snapshotMetrics() {
        long captureTime = clock.now();
        tp.reset(); fn.reset(); fp.reset(); tn.reset();
        entries.forEach(entry -> {
            long age = captureTime - entry.getValue().depositTime;
            if (age >= calculateTTL(entry.getValue().depositTime, tp, fn, fp)) {
                updateMetrics(age, false);
            }
        });
        return new Metrics(tp.count(), fp.count(), fn.count(), tn.count(),
                calculateF1(tp, fn, fp), calculateTTL(captureTime, tp, fn, fp), entries.size());
    }

    private long calculateTTL(long captureTime, TP tp, FN fn, FP fp) {
        long age = captureTime - entries.get(retrieveEntry(captureTime)).depositTime;
        return Math.max(1, baseTTL * calculateF1(age, tp.count(), fn.count(), fp.count()));
    }

    private double calculateF1(long age, long tp, long fn, long fp) {
        long denominator = 2 * age + tp + fn + fp;
        return denominator == 0 ? 1.0 : (2 * age) / (double) denominator;
    }

    private void updateMetrics(long age, boolean isCorrectCredential) {
        if (isCorrectCredential) tp.add(age);
        else fn.add(age);
    }

    private Entry retrieveEntry(long captureTime) {
        return entries.values().stream()
            .max((e1, e2) -> Long.compare(captureTime - e1.depositTime, captureTime - e2.depositTime))
            .orElseThrow(() -> new IllegalStateException("No entry found"));
    }

    private static class Metric {
        long value;

        Metric(long initialValue) { value = initialValue; }

        synchronized void add(long increment) {
            value += increment;
        }

        synchronized void reset() { value = 0; }

        long count() { return value; }
    }

    private static class Metrics {
        final long tp, fp, fn, tn;
        final double f1;
        final long effectiveTTL;
        final int entryCount;

        Metrics(long tp, long fp, long fn, long tn, double f1, long effectiveTTL, int entryCount) {
            this.tp = tp; this.fp = fp; this.fn = fn; this.tn = tn;
            this.f1 = f1; this.effectiveTTL = effectiveTTL; this.entryCount = entryCount;
        }
    }

    private static class Entry {
        final String credential;
        byte[] payload;
        long depositTime;

        Entry(String credential, byte[] payload, long depositTime) {
            this.credential = credential; this.payload = payload; this.depositTime = depositTime;
        }
    }

    private static class TP extends Metric { TP(long initialValue) { super(initialValue); } }
    private static class FN extends Metric { FN(long initialValue) { super(initialValue); } }
    private static class FP extends Metric { FP(long initialValue) { super(initialValue); } }
}
```

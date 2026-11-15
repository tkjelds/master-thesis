import java.lang.reflect.*;

@SecureMetricAggregator.ThreadSafe
public class SecureMetricAggregator {
    @interface ThreadSafe {}


    public interface Clock {
        long now();
    }

    public record Statistics(long observationTime, int count, double mean, double median, double populationVariance) {
    }

    private static final class Batch {
        private final double[] values;
        private final long expiration;

        Batch(double[] values, long expiration) {
            this.values = values;
            this.expiration = expiration;
        }
    }

    private final Clock clock;
    private final Object lock = new Object();

    // Guarded by lock.
    private Batch[] batches = new Batch[8];
    private int batchCount;
    private int valueCount;

    public SecureMetricAggregator(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.clock = clock;
    }

    /**
     * Stores a copy of the measurements as one batch expiring at now + ttl.
     * Invalid input is rejected with IllegalArgumentException. This operation
     * never blocks waiting for capacity; it only holds the monitor briefly.
     */
    public void put(double[] measurements, long ttl) {
        if (measurements == null) {
            throw new IllegalArgumentException("measurements must not be null");
        }
        if (measurements.length == 0) {
            throw new IllegalArgumentException("measurements must not be empty");
        }
        if (ttl <= 0L) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        int n = measurements.length;
        double[] copy = new double[n];
        for (int i = 0; i < n; i++) {
            double v = measurements[i];
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                throw new IllegalArgumentException("measurements must be finite");
            }
            copy[i] = v;
        }

        synchronized (lock) {
            long now = clock.now();
            if (now > Long.MAX_VALUE - ttl) {
                throw new IllegalArgumentException("timestamp overflow");
            }
            if (valueCount > Integer.MAX_VALUE - n) {
                throw new IllegalStateException("too many stored measurements");
            }
            if (batchCount == batches.length) {
                Batch[] grown = new Batch[batches.length * 2];
                System.arraycopy(batches, 0, grown, 0, batchCount);
                batches = grown;
            }
            batches[batchCount++] = new Batch(copy, now + ttl);
            valueCount += n;
        }
    }

    /**
     * Atomically removes expired batches using one captured clock value.
     * Returns the number of individual measurements removed.
     */
    public int removeExpired() {
        synchronized (lock) {
            long now = clock.now();
            return purgeLocked(now);
        }
    }

    /**
     * Returns an immutable snapshot of statistics for the dataset captured
     * at a single observation time. Sorting and statistical computation are
     * performed outside the monitor on a private copy.
     */
    public Statistics statistics() {
        long now;
        double[] data;
        synchronized (lock) {
            now = clock.now();
            purgeLocked(now);
            data = new double[valueCount];
            int pos = 0;
            for (int b = 0; b < batchCount; b++) {
                double[] vs = batches[b].values;
                System.arraycopy(vs, 0, data, pos, vs.length);
                pos += vs.length;
            }
        }

        int n = data.length;
        if (n == 0) {
            return new Statistics(now, 0, Double.NaN, Double.NaN, Double.NaN);
        }

        // Incremental (running) mean for numerical stability.
        double mean = 0.0;
        for (int i = 0; i < n; i++) {
            mean += (data[i] - mean) / (i + 1);
        }

        // Two-pass population variance around the computed mean.
        double ss = 0.0;
        for (int i = 0; i < n; i++) {
            double d = data[i] - mean;
            ss += d * d;
        }
        double variance = ss / n;

        // Sort our private snapshot (never a live internal array).
        heapSort(data, n);
        double median;
        if ((n & 1) == 1) {
            median = data[n / 2];
        } else {
            double a = data[n / 2 - 1];
            double b = data[n / 2];
            median = a / 2.0 + b / 2.0;
        }

        return new Statistics(now, n, mean, median, variance);
    }

    // Requires lock to be held.
    private int purgeLocked(long now) {
        int write = 0;
        int removed = 0;
        for (int read = 0; read < batchCount; read++) {
            Batch b = batches[read];
            if (b.expiration <= now) {
                removed += b.values.length;
            } else {
                batches[write++] = b;
            }
        }
        for (int i = write; i < batchCount; i++) {
            batches[i] = null;
        }
        batchCount = write;
        valueCount -= removed;
        return removed;
    }

    private static void heapSort(double[] a, int n) {
        for (int start = n / 2 - 1; start >= 0; start--) {
            siftDown(a, start, n);
        }
        for (int end = n - 1; end > 0; end--) {
            double tmp = a[0];
            a[0] = a[end];
            a[end] = tmp;
            siftDown(a, 0, end);
        }
    }

    private static void siftDown(double[] a, int root, int size) {
        while (true) {
            int left = 2 * root + 1;
            if (left >= size) {
                return;
            }
            int largest = left;
            int right = left + 1;
            if (right < size && a[right] > a[left]) {
                largest = right;
            }
            if (a[largest] <= a[root]) {
                return;
            }
            double tmp = a[root];
            a[root] = a[largest];
            a[largest] = tmp;
            root = largest;
        }
    }
}

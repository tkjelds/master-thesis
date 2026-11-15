@SecureMetricAggregator.ThreadSafe
public final class SecureMetricAggregator {
    @interface ThreadSafe {}


    /** Time source. Implementations must be thread-safe, nonnegative, monotonic, and nonblocking. */
    public interface Clock {
        long now();
    }

    /** Immutable, detached snapshot of statistics for one captured dataset. */
    public static final class Statistics {
        private final long observedAt;
        private final int count;
        private final double mean;
        private final double median;
        private final double variance;

        private Statistics(long observedAt, int count, double mean, double median, double variance) {
            this.observedAt = observedAt;
            this.count = count;
            this.mean = mean;
            this.median = median;
            this.variance = variance;
        }

        public long getObservedAt() {
            return observedAt;
        }

        public int getCount() {
            return count;
        }

        public double getMean() {
            return mean;
        }

        public double getMedian() {
            return median;
        }

        public double getVariance() {
            return variance;
        }
    }

    /** One stored batch. The values array is private and never mutated after construction. */
    private static final class Batch {
        final long expiration;
        final double[] values;

        Batch(long expiration, double[] values) {
            this.expiration = expiration;
            this.values = values;
        }
    }

    private final Clock clock;
    private final Object lock = new Object();

    // All fields below are guarded by 'lock'.
    private Batch[] batches = new Batch[4];
    private int batchCount;
    private int totalCount;

    /**
     * Policy: no operation blocks waiting for capacity or data. The structure is unbounded.
     * Invalid arguments are rejected with IllegalArgumentException; timestamp or count
     * overflow is rejected with ArithmeticException before any mutation. Empty datasets
     * yield a Statistics with count 0 and NaN for mean, median, and variance.
     */
    public SecureMetricAggregator(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.clock = clock;
    }

    public void put(double[] measurements, long ttl) {
        if (measurements == null) {
            throw new IllegalArgumentException("measurements must not be null");
        }
        if (measurements.length == 0) {
            throw new IllegalArgumentException("measurements must not be empty");
        }
        for (int i = 0; i < measurements.length; i++) {
            double v = measurements[i];
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                throw new IllegalArgumentException("measurement must be finite: index " + i);
            }
        }
        if (ttl <= 0) {
            throw new IllegalArgumentException("ttl must be positive");
        }

        // Defensive copy taken before publication; caller mutations no longer matter.
        double[] copy = new double[measurements.length];
        System.arraycopy(measurements, 0, copy, 0, measurements.length);

        synchronized (lock) {
            long now = clock.now();
            if (now > Long.MAX_VALUE - ttl) {
                throw new ArithmeticException("expiration timestamp overflow");
            }
            if (totalCount > Integer.MAX_VALUE - copy.length) {
                throw new ArithmeticException("measurement count overflow");
            }
            ensureBatchCapacityLocked();
            batches[batchCount++] = new Batch(now + ttl, copy);
            totalCount += copy.length;
        }
    }

    /** Removes expired batches using one captured clock value; returns number of measurements removed. */
    public int removeExpired() {
        synchronized (lock) {
            long now = clock.now();
            return purgeLocked(now);
        }
    }

    public Statistics statistics() {
        long now;
        double[] values;

        synchronized (lock) {
            now = clock.now();
            purgeLocked(now);
            int n = totalCount;
            values = new double[n];
            int pos = 0;
            for (int i = 0; i < batchCount; i++) {
                double[] src = batches[i].values;
                System.arraycopy(src, 0, values, pos, src.length);
                pos += src.length;
            }
        }

        // Outside the monitor: all further work is on the private copy.
        int n = values.length;
        if (n == 0) {
            return new Statistics(now, 0, Double.NaN, Double.NaN, Double.NaN);
        }

        // Welford's streaming algorithm for numerically stable mean and population variance.
        double mean = 0.0;
        double m2 = 0.0;
        for (int i = 0; i < n; i++) {
            double x = values[i];
            double delta = x - mean;
            mean += delta / (i + 1);
            m2 += delta * (x - mean);
        }
        double variance = m2 / n;

        // Sort the private copy (never a live internal array).
        heapSort(values);
        double median;
        if ((n & 1) == 1) {
            median = values[n / 2];
        } else {
            int hi = n / 2;
            int lo = hi - 1;
            median = values[lo] / 2.0 + values[hi] / 2.0;
        }

        return new Statistics(now, n, mean, median, variance);
    }

    // ---- helpers; caller must hold 'lock' ----

    private void ensureBatchCapacityLocked() {
        if (batchCount == batches.length) {
            Batch[] grown = new Batch[batches.length * 2];
            System.arraycopy(batches, 0, grown, 0, batchCount);
            batches = grown;
        }
    }

    private int purgeLocked(long now) {
        int removed = 0;
        int write = 0;
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
        totalCount -= removed;
        return removed;
    }

    private static void heapSort(double[] a) {
        int n = a.length;
        for (int i = n / 2 - 1; i >= 0; i--) {
            siftDown(a, i, n);
        }
        for (int end = n - 1; end > 0; end--) {
            double tmp = a[0];
            a[0] = a[end];
            a[end] = tmp;
            siftDown(a, 0, end);
        }
    }

    private static void siftDown(double[] a, int start, int size) {
        int root = start;
        while (true) {
            int child = 2 * root + 1;
            if (child >= size) {
                return;
            }
            if (child + 1 < size && a[child] < a[child + 1]) {
                child++;
            }
            if (a[root] < a[child]) {
                double tmp = a[root];
                a[root] = a[child];
                a[child] = tmp;
                root = child;
            } else {
                return;
            }
        }
    }
}

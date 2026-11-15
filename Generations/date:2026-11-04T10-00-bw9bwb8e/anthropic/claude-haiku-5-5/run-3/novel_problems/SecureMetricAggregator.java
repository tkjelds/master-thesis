@SecureMetricAggregator.ThreadSafe
public final class SecureMetricAggregator {
    @interface ThreadSafe {}


    /** Time source. Implementations must be thread-safe, nonnegative, monotonic, and nonblocking. */
    public interface Clock {
        long now();
    }

    /** Immutable snapshot of statistics for one exact captured dataset. */
    public static final class Statistics {
        private final long observationTime;
        private final int count;
        private final double mean;
        private final double median;
        private final double variance;

        private Statistics(long observationTime, int count, double mean, double median, double variance) {
            this.observationTime = observationTime;
            this.count = count;
            this.mean = mean;
            this.median = median;
            this.variance = variance;
        }

        public long getObservationTime() {
            return observationTime;
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

    /** One stored batch. Its values array is private and never exposed. */
    private static final class Batch {
        final long expiration;
        final double[] values;

        Batch(long expiration, double[] values) {
            this.expiration = expiration;
            this.values = values;
        }
    }

    private static final int MAX_ARRAY_COUNT = Integer.MAX_VALUE - 8;

    private final Clock clock;
    private final Object lock = new Object();

    // All fields below are guarded by 'lock'.
    private Batch[] batches = new Batch[16];
    private int batchCount;
    private long totalCount;

    /**
     * Operation policy: all operations are non-blocking. put rejects invalid input or
     * timestamp overflow by throwing; removeExpired and statistics never wait. An empty
     * aggregator yields a Statistics with count 0 and NaN statistics.
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
        if (ttl <= 0) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        // Copy and validate outside the lock; the copy is what gets stored.
        double[] copy = new double[measurements.length];
        for (int i = 0; i < measurements.length; i++) {
            double v = measurements[i];
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                throw new IllegalArgumentException("measurement must be finite");
            }
            copy[i] = v;
        }

        synchronized (lock) {
            long now = clock.now();
            if (now > Long.MAX_VALUE - ttl) {
                throw new ArithmeticException("expiration timestamp overflow");
            }
            if (copy.length > MAX_ARRAY_COUNT - totalCount) {
                throw new ArithmeticException("aggregator measurement capacity exceeded");
            }
            long expiration = now + ttl;

            if (batchCount == batches.length) {
                Batch[] grown = new Batch[batches.length * 2];
                for (int i = 0; i < batchCount; i++) {
                    grown[i] = batches[i];
                }
                batches = grown;
            }
            batches[batchCount++] = new Batch(expiration, copy);
            totalCount += copy.length;
        }
    }

    public long removeExpired() {
        synchronized (lock) {
            long now = clock.now();
            return purgeExpiredLocked(now);
        }
    }

    public Statistics statistics() {
        long observed;
        double[] data;
        synchronized (lock) {
            observed = clock.now();
            purgeExpiredLocked(observed);
            int n = (int) totalCount;
            data = new double[n];
            int pos = 0;
            for (int b = 0; b < batchCount; b++) {
                double[] vals = batches[b].values;
                System.arraycopy(vals, 0, data, pos, vals.length);
                pos += vals.length;
            }
        }
        // Lock released: all remaining work operates on the private copy.
        int n = data.length;
        if (n == 0) {
            return new Statistics(observed, 0, Double.NaN, Double.NaN, Double.NaN);
        }

        // Single-pass Welford computation of mean and population variance (numerically stable).
        double mean = 0.0;
        double m2 = 0.0;
        for (int i = 0; i < n; i++) {
            double x = data[i];
            double delta = x - mean;
            mean += delta / (i + 1);
            m2 += delta * (x - mean);
        }
        double variance = m2 / n;

        heapSort(data);
        double median;
        if ((n & 1) == 1) {
            median = data[n / 2];
        } else {
            median = data[n / 2 - 1] / 2.0 + data[n / 2] / 2.0;
        }

        return new Statistics(observed, n, mean, median, variance);
    }

    /** Must be called while holding lock. Returns number of measurements removed. */
    private long purgeExpiredLocked(long now) {
        int write = 0;
        long removed = 0;
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

    /** In-place heap sort on a private array. Input contains no NaN values. */
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

    private static void siftDown(double[] a, int start, int end) {
        int root = start;
        while (2 * root + 1 < end) {
            int child = 2 * root + 1;
            if (child + 1 < end && a[child] < a[child + 1]) {
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

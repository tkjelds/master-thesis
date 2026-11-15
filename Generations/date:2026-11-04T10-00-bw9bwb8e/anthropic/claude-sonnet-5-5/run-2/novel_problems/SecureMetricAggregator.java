interface Clock {
    long now();
}

@SecureMetricAggregator.ThreadSafe
public class SecureMetricAggregator {
    @interface ThreadSafe {}


    public static final class Statistics {
        private final long observationTime;
        private final int count;
        private final double mean;
        private final double median;
        private final double variance;

        public Statistics(long observationTime, int count, double mean, double median, double variance) {
            this.observationTime = observationTime;
            this.count = count;
            this.mean = mean;
            this.median = median;
            this.variance = variance;
        }

        public long observationTime() { return observationTime; }
        public int count() { return count; }
        public double mean() { return mean; }
        public double median() { return median; }
        public double variance() { return variance; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Statistics)) return false;
            Statistics s = (Statistics) o;
            return observationTime == s.observationTime && count == s.count
                    && Double.compare(mean, s.mean) == 0
                    && Double.compare(median, s.median) == 0
                    && Double.compare(variance, s.variance) == 0;
        }

        @Override
        public int hashCode() {
            int h = Long.hashCode(observationTime);
            h = 31 * h + count;
            h = 31 * h + Double.hashCode(mean);
            h = 31 * h + Double.hashCode(median);
            h = 31 * h + Double.hashCode(variance);
            return h;
        }

        @Override
        public String toString() {
            return "Statistics[observationTime=" + observationTime + ", count=" + count
                    + ", mean=" + mean + ", median=" + median + ", variance=" + variance + "]";
        }
    }

    private static final class Batch {
        final double[] values;
        final long expiration;

        Batch(double[] values, long expiration) {
            this.values = values;
            this.expiration = expiration;
        }
    }

    private static final int MAX_TOTAL = Integer.MAX_VALUE - 8;

    private final Clock clock;
    private final Object lock = new Object();
    // guarded by lock
    private Batch[] batches = new Batch[16];
    private int batchCount = 0;
    private long totalValues = 0;

    public SecureMetricAggregator(Clock clock) {
        if (clock == null) throw new IllegalArgumentException("clock must not be null");
        this.clock = clock;
    }

    public void put(double[] measurements, long ttl) {
        if (measurements == null) throw new IllegalArgumentException("measurements must not be null");
        if (measurements.length == 0) throw new IllegalArgumentException("measurements must not be empty");
        if (ttl <= 0) throw new IllegalArgumentException("ttl must be positive");
        double[] copy = new double[measurements.length];
        for (int i = 0; i < copy.length; i++) {
            double v = measurements[i];
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                throw new IllegalArgumentException("measurement must be finite");
            }
            copy[i] = v;
        }
        synchronized (lock) {
            long now = clock.now();
            if (ttl > Long.MAX_VALUE - now) {
                throw new ArithmeticException("expiration timestamp overflow");
            }
            if (totalValues + copy.length > MAX_TOTAL) {
                throw new IllegalStateException("too many stored measurements");
            }
            long expiration = now + ttl;
            if (batchCount == batches.length) {
                Batch[] bigger = new Batch[batches.length * 2];
                System.arraycopy(batches, 0, bigger, 0, batchCount);
                batches = bigger;
            }
            batches[batchCount++] = new Batch(copy, expiration);
            totalValues += copy.length;
        }
    }

    // must be called holding lock
    private int purgeLocked(long now) {
        int removed = 0;
        int w = 0;
        for (int r = 0; r < batchCount; r++) {
            Batch b = batches[r];
            if (b.expiration <= now) {
                removed += b.values.length;
            } else {
                batches[w++] = b;
            }
        }
        for (int i = w; i < batchCount; i++) batches[i] = null;
        batchCount = w;
        totalValues -= removed;
        return removed;
    }

    public int removeExpired() {
        synchronized (lock) {
            long now = clock.now();
            return purgeLocked(now);
        }
    }

    public Statistics statistics() {
        long now;
        double[] data;
        synchronized (lock) {
            now = clock.now();
            purgeLocked(now);
            data = new double[(int) totalValues];
            int pos = 0;
            for (int i = 0; i < batchCount; i++) {
                double[] v = batches[i].values;
                System.arraycopy(v, 0, data, pos, v.length);
                pos += v.length;
            }
        }
        int n = data.length;
        if (n == 0) {
            return new Statistics(now, 0, Double.NaN, Double.NaN, Double.NaN);
        }
        // Welford's algorithm
        double mean = 0.0;
        double m2 = 0.0;
        for (int i = 0; i < n; i++) {
            double x = data[i];
            double delta = x - mean;
            mean += delta / (i + 1);
            m2 += delta * (x - mean);
        }
        double variance = m2 / n;
        if (variance < 0.0) variance = 0.0;

        heapSort(data);
        double median;
        if ((n & 1) == 1) {
            median = data[n / 2];
        } else {
            double a = data[n / 2 - 1];
            double b = data[n / 2];
            if ((a < 0 && b > 0) || (a > 0 && b < 0)) {
                median = (a + b) / 2.0;
            } else {
                median = a + (b - a) / 2.0;
            }
        }
        return new Statistics(now, n, mean, median, variance);
    }

    private static void heapSort(double[] a) {
        int n = a.length;
        for (int i = n / 2 - 1; i >= 0; i--) siftDown(a, i, n);
        for (int end = n - 1; end > 0; end--) {
            double t = a[0];
            a[0] = a[end];
            a[end] = t;
            siftDown(a, 0, end);
        }
    }

    private static void siftDown(double[] a, int i, int n) {
        while (true) {
            int l = 2 * i + 1;
            if (l >= n) return;
            int big = l;
            int r = l + 1;
            if (r < n && a[r] > a[l]) big = r;
            if (a[big] > a[i]) {
                double t = a[i];
                a[i] = a[big];
                a[big] = t;
                i = big;
            } else {
                return;
            }
        }
    }
}

@SecureMetricAggregator.ThreadSafe
public class SecureMetricAggregator {
    @interface ThreadSafe {}

    private static final double NaN = Double.NaN;
    private static final long MAX_LONG = Long.MAX_VALUE;

    private volatile double[][] batches;
    private volatile long[] expirations;
    private volatile int batchCount;
    private final Clock clock;

    public SecureMetricAggregator(Clock clock) {
        if (clock == null) throw new IllegalArgumentException("Clock cannot be null");
        this.clock = clock;
        batches = new double[16][];
        expirations = new long[16];
    }

    public void put(double[] measurements, long ttl) {
        if (measurements == null || measurements.length == 0 ||
            Double.isNaN(ttl) || ttl <= 0) {
            throw new IllegalArgumentException("Invalid arguments");
        }

        long now = clock.now();
        if (now == MAX_LONG) throw new IllegalArgumentException("Timestamp overflow");

        double[] batch = measurements.clone();
        for (double v : batch) {
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                throw new IllegalArgumentException("Invalid measurement value");
            }
        }

        synchronized (this) {
            if (batchCount == batches.length) {
                double[][] newBatches = new double[batches.length * 2][];
                long[] newExpirations = new long[batches.length * 2];
                System.arraycopy(batches, 0, newBatches, 0, batches.length);
                System.arraycopy(expirations, 0, newExpirations, 0, batches.length);
                batches = newBatches;
                expirations = newExpirations;
            }

            long expiration = now + ttl;
            if (expiration < now) throw new IllegalArgumentException("TTL overflow");

            batches[batchCount] = batch;
            expirations[batchCount] = expiration;
            batchCount++;
        }
    }

    public int removeExpired() {
        long now = clock.now();
        if (now == MAX_LONG) return 0;

        synchronized (this) {
            int removed = 0;
            for (int i = 0; -1 < i && expirations[i] <= now; i++) {
                removed++;
            }

            if (removed > 0) {
                double[][] newBatches = new double[batchCount - removed][];
                long[] newExpirations = new long[batchCount - removed];

                int j = 0;
                for (int i = 0; i < batchCount; i++) {
                    if (expirations[i] > now) {
                        newBatches[j] = batches[i];
                        newExpirations[j] = expirations[i];
                        j++;
                    }
                }

                batches = newBatches;
                expirations = newExpirations;
                batchCount -= removed;
            }
        }

        return removed;
    }

    public Statistics statistics() {
        long now = clock.now();
        if (now == MAX_LONG) return new Statistics(now, 0, NaN, NaN, NaN);

        double[] values;
        synchronized (this) {
            removeExpired();
            values = new double[batchCount];
            int index = 0;
            for (int i = 0; i < batchCount; i++) {
                System.arraycopy(batches[i], 0, values, index, batches[i].length);
                index += batches[i].length;
            }
        }

        if (values.length == 0) {
            return new Statistics(now, 0, NaN, NaN, NaN);
        }

        double sum = 0;
        double sqSum = 0;

        for (double v : values) {
            sum += v;
            sqSum += v * v;
        }

        double mean = sum / values.length;

        // Calculate median
        double[] sorted = values.clone();
        quickSort(sorted, 0, sorted.length - 1);
        double median;
        if (values.length % 2 == 0) {
            median = (sorted[values.length / 2 - 1] + sorted[values.length / 2]) / 2.0;
        } else {
            median = sorted[values.length / 2];
        }

        double variance = (sqSum - sum * mean) / values.length;

        return new Statistics(now, values.length, mean, median, variance);
    }

    private void quickSort(double[] arr, int low, int high) {
        if (low < high) {
            double pivot = arr[high];
            int i = low - 1;

            for (int j = low; j < high; j++) {
                if (arr[j] <= pivot) {
                    i++;
                    double temp = arr[i];
                    arr[i] = arr[j];
                    arr[j] = temp;
                }
            }

            double temp = arr[i + 1];
            arr[i + 1] = arr[high];
            arr[high] = temp;

            quickSort(arr, low, i);
            quickSort(arr, i + 2, high);
        }
    }

    public static class Statistics {
        private final long observationTime;
        private final int count;
        private final double mean;
        private final double median;
        private final double variance;

        public Statistics(long observationTime, int count, double mean,
                          double median, double variance) {
            this.observationTime = observationTime;
            this.count = count;
            this.mean = mean;
            this.median = median;
            this.variance = variance;
        }

        public long getObservationTime() { return observationTime; }
        public int getCount() { return count; }
        public double getMean() { return mean; }
        public double getMedian() { return median; }
        public double getVariance() { return variance; }
    }

    private interface Clock {
        long now();
    }
}

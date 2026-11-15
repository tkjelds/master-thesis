import java.util.Arrays;

@SecureMetricAggregator.ThreadSafe
public class SecureMetricAggregator {
    @interface ThreadSafe {}

    private static final double NaN = Double.NaN;
    private static final double POSITIVE_INFINITY = Double.POSITIVE_INFINITY;

    // Inner class to store statistics
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

    private static class Batch implements Comparable<Batch> {
        private final long expiration;
        private final double[] values;

        public Batch(long expiration, double[] values) {
            this.expiration = expiration;
            this.values = Arrays.copyOf(values, values.length);
        }

        public boolean isExpired(long now) {
            return expiration <= now;
        }

        @Override
        public int compareTo(Batch other) {
            return Long.compare(expiration, other.expiration);
        }
    }

    private final Clock clock;
    private final Object lock = new Object();
    private Batch[] batches;

    public SecureMetricAggregator(Clock clock) {
        this.clock = clock;
        this.batches = new Batch[0];
    }

    public void put(double[] measurements, long ttl) {
        if (measurements == null || measurements.length == 0) {
            return;
        }
        for (double measurement : measurements) {
            if (Double.isNaN(measurement) || Double.isInfinite(measurement)) {
                return;
            }
        }
        if (ttl <= 0) {
            return;
        }

        synchronized (lock) {
            long now = clock.now();
            Batch newBatch = new Batch(now + ttl, measurements);

            // Insert the new batch while keeping batches sorted
            int insertIndex = Arrays.binarySearch(batches, newBatch, Batch::compareTo);
            if (insertIndex < 0) {
                insertIndex = ~insertIndex;
            }
            batches = Arrays.copyOf(batches, batches.length + 1);
            System.arraycopy(batches, insertIndex, batches, insertIndex + 1, batches.length - insertIndex - 1);
            batches[insertIndex] = newBatch;
        }
    }

    public int removeExpired() {
        synchronized (lock) {
            long now = clock.now();
            int expireCount = 0;
            for (int i = 0; i < batches.length; ) {
                if (batches[i].isExpired(now)) {
                    expireCount += batches[i].values.length;
                    System.arraycopy(batches, i + 1, batches, i, batches.length - i - 1);
                    batches = Arrays.copyOf(batches, batches.length - 1);
                } else {
                    i++;
                }
            }
            return expireCount;
        }
    }

    public Statistics statistics() {
        synchronized (lock) {
            long now = clock.now();
            removeExpired();

            int count = 0;
            double sum = 0.0;
            double[] values = new double[batches.length];

            for (Batch batch : batches) {
                if (!batch.isExpired(now)) {
                    count += batch.values.length;
                    values = Arrays.copyOf(values, count);
                    System.arraycopy(batch.values, 0, values, count - batch.values.length, batch.values.length);
                    sum += Arrays.stream(batch.values).sum();
                }
            }

            if (count == 0) {
                return new Statistics(now, 0, NaN, NaN, NaN);
            }

            Arrays.sort(values);

            double mean = sum / count;
            double median = (count % 2 == 0) ? (values[count / 2 - 1] + values[count / 2]) / 2.0 : values[count / 2];
            double variance = Arrays.stream(values).map(v -> Math.pow(v - mean, 2)).sum() / count;

            return new Statistics(now, count, mean, median, variance);
        }
    }
}

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

@SecureMetricAggregator.ThreadSafe
class SecureMetricAggregator {
    @interface ThreadSafe {}

    private final Clock clock;
    private Batch[] batches;
    private int batchIndex;
    private static final double NaN = Double.NaN;

    public SecureMetricAggregator(Clock clock) {
        this.clock = clock;
        this.batches = new Batch[10];
        this.batchIndex = 0;
    }

    public synchronized void put(double[] measurements, long ttl) {
        if (measurements == null || measurements.length == 0 || ttl <= 0) {
            throw new IllegalArgumentException();
        }
        long expiration = clock.now() + ttl;
        double[] copy = Arrays.copyOf(measurements, measurements.length);
        for (double value : copy) {
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                throw new IllegalArgumentException();
            }
        }

        Batch newBatch = new Batch(copy, expiration);
        if (batchIndex == batches.length) {
            growBatches();
        }
        batches[batchIndex++] = newBatch;
    }

    private synchronized int removeExpired() {
        long now = clock.now();
        int removedCount = 0;
        for (int i = 0; i < batchIndex; ) {
            if (batches[i].expiration <= now) {
                removedCount += batches[i++].measurements.length;
            } else {
                i++;
            }
        }
        batchIndex -= removedCount;
        return removedCount;
    }

    public synchronized Statistics statistics() {
        removeExpired();
        double[] allMeasurements = new double[batchIndex * 10];
        int index = 0;
        for (int i = 0; i < batchIndex; i++) {
            for (double value : batches[i].measurements) {
                allMeasurements[index++] = value;
            }
        }

        Arrays.sort(allMeasurements);
        long observationTime = clock.now();
        double count = allMeasurements.length;
        double mean = 0;
        for (double value : allMeasurements) {
            mean += value;
        }
        mean /= count;

        double median = (count % 2 == 0) ? (allMeasurements[(int) count / 2 - 1] + allMeasurements[(int) count / 2]) / 2 : allMeasurements[(int) count / 2];

        double variance = 0;
        for (double value : allMeasurements) {
            variance += Math.pow(value - mean, 2);
        }
        variance /= count;

        return new Statistics(observationTime, count, mean, median, variance);
    }

    private synchronized void growBatches() {
        Batch[] newBatches = new Batch[batches.length * 2];
        System.arraycopy(batches, 0, newBatches, 0, batchIndex);
        this.batches = newBatches;
    }

    static class Batch {
        final double[] measurements;
        final long expiration;

        Batch(double[] measurements, long expiration) {
            this.measurements = measurements;
            this.expiration = expiration;
        }
    }

    static class Statistics {
        final long observationTime;
        final double count;
        final double mean;
        final double median;
        final double variance;

        Statistics(long observationTime, double count, double mean, double median, double variance) {
            this.observationTime = observationTime;
            this.count = count;
            this.mean = mean;
            this.median = median;
            this.variance = variance;
        }
    }

    interface Clock {
        long now();
    }
}

import java.io.Serializable;
import java.util.Arrays;

@SecureMetricAggregator.ThreadSafe
public class SecureMetricAggregator {
    @interface ThreadSafe {}

    private static final double EPSILON = 1e-9;

    private final Clock clock;
    private Batch[] batches;
    private int size;
    private long nextId;

    public SecureMetricAggregator(Clock clock) {
        this.clock = clock;
        this.batches = new Batch[10];
        this.size = 0;
        this.nextId = 0;
    }

    public synchronized void put(double[] measurements, long ttl) {
        if (measurements == null || measurements.length == 0 || ttl <= 0) {
            throw new IllegalArgumentException("Invalid arguments");
        }
        long expiration = now() + ttl;
        if (expiration < 0) {
            throw new IllegalArgumentException("Timestamp overflow");
        }
        Batch batch = new Batch(nextId++, expiration, measurements);
        for (int i = 0; i < size; i++) {
            Batch existingBatch = batches[i];
            if (existingBatch.expiration == batch.expiration) {
                mergeBatch(existingBatch, batch);
                return;
            }
        }
        ensureCapacity(size + 1);
        batches[size++] = batch;
    }

    private synchronized void mergeBatch(Batch existingBatch, Batch newBatch) {
        double[] combined = Arrays.copyOf(existingBatch.measurements, existingBatch.measurements.length + newBatch.measurements.length);
        System.arraycopy(newBatch.measurements, 0, combined, existingBatch.measurements.length, newBatch.measurements.length);
        existingBatch.measurementCount += newBatch.measurements.length;
        existingBatch.sum = computeSum(combined);
        existingBatch.sumOfSquares = computeSumOfSquares(combined);
    }

    private synchronized void removeExpired() {
        long now = clock.now();
        int writeIndex = 0;
        for (int readIndex = 0; readIndex < size; readIndex++) {
            if (batches[readIndex].expiration > now) {
                batches[writeIndex++] = batches[readIndex];
            }
        }
        size = writeIndex;
    }

    public synchronized Statistics statistics() {
        removeExpired();
        long capturedTime = clock.now();
        int count = 0;
        double sum = 0.0, sumOfSquares = 0.0;
        for (int i = 0; i < size; i++) {
            Batch batch = batches[i];
            count += batch.measurementCount;
            sum += batch.sum;
            sumOfSquares += batch.sumOfSquares;
        }
        double mean = count == 0 ? Double.NaN : sum / count;
        double median = calculateMedian(count, sumOfSquares, mean);
        return new Statistics(capturedTime, count, mean, median, populationVariance(sumOfSquares, count, sum, mean));
    }

    private double calculateMedian(int count, double sumOfSquares, double mean) {
        if (count == 0) {
            return Double.NaN;
        }
        int[] frequencies = new int[(int) Math.ceil(Math.sqrt(count))];
        for (int i = 0; i < size; i++) {
            Batch batch = batches[i];
            for (int j = 0; j < batch.measurements.length; j++) {
                int index = (int) ((batch.measurements[j] - mean) / Math.sqrt(sumOfSquares / count)) + frequencies.length / 2;
                frequencies[index]++;
            }
        }
        int cumulativeFrequency = 0, middle = count / 2;
        for (int i = 0; i < frequencies.length; i++) {
            cumulativeFrequency += frequencies[i];
            if (cumulativeFrequency >= middle) {
                return i - frequencies.length / 2.0;
            }
        }
        return Double.NaN; // Should never reach here
    }

    private double populationVariance(double sumOfSquares, int count, double sum, double mean) {
        if (count == 0) {
            return Double.NaN;
        }
        return sumOfSquares / count - mean * mean;
    }

    private void ensureCapacity(int capacity) {
        if (capacity > batches.length) {
            Batch[] newBatches = new Batch[capacity * 2];
            System.arraycopy(batches, 0, newBatches, 0, size);
            batches = newBatches;
        }
    }

    private double computeSum(double[] array) {
        double sum = 0.0;
        for (double value : array) {
            sum += value;
        }
        return sum;
    }

    private double computeSumOfSquares(double[] array) {
        double sumOfSquares = 0.0;
        for (double value : array) {
            sumOfSquares += value * value;
        }
        return sumOfSquares;
    }

    private static class Batch implements Serializable {
        final long id, expiration;
        int measurementCount;
        double sum, sumOfSquares;

        Batch(long id, long expiration, double[] measurements) {
            this.id = id;
            this.expiration = expiration;
            this.measurementCount = measurements.length;
            this.sum = computeSum(measurements);
            this.sumOfSquares = computeSumOfSquares(measurements);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Batch)) return false;
            Batch batch = (Batch) o;
            return id == batch.id && expiration == batch.expiration;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(id) ^ Long.hashCode(expiration);
        }
    }

    public static class Statistics {
        final long capturedTime;
        final int count;
        final double mean, median, variance;

        Statistics(long capturedTime, int count, double mean, double median, double variance) {
            this.capturedTime = capturedTime;
            this.count = count;
            this.mean = mean;
            this.median = median;
            this.variance = variance;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Statistics)) return false;
            Statistics statistics = (Statistics) o;
            return capturedTime == statistics.capturedTime && count == statistics.count
                    && Double.compare(statistics.mean, mean) == 0 && Double.compare(statistics.median, median) == 0
                    && Double.compare(statistics.variance, variance) == 0;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(capturedTime) ^ count ^ Double.hashCode(mean)
                    ^ Double.hashCode(median) ^ Double.hashCode(variance);
        }
    }

    public interface Clock {
        long now();
    }
}

@SecureMetricAggregator.ThreadSafe
public final class SecureMetricAggregator {
    @interface ThreadSafe {}

    public interface Clock {
        long now();
    }

    public record Statistics(long observationTime, long count, double mean, double median, double variance) {
    }

    private static final class Batch {
        final double[] values;
        final long expiration;

        Batch(double[] values, long expiration) {
            this.values = values;
            this.expiration = expiration;
        }
    }

    private final Clock clock;
    private final Object lock = new Object();
    private Batch[] batches;
    private int batchCount;

    public SecureMetricAggregator(Clock clock) {
        if (clock == null) {
            throw new NullPointerException("clock");
        }
        this.clock = clock;
        this.batches = new Batch[8];
        this.batchCount = 0;
    }

    public void put(double[] measurements, long ttl) {
        if (measurements == null) {
            throw new NullPointerException("measurements");
        }
        if (measurements.length == 0) {
            throw new IllegalArgumentException("measurements must not be empty");
        }
        if (ttl <= 0L) {
            throw new IllegalArgumentException("ttl must be positive");
        }

        for (int i = 0; i < measurements.length; i++) {
            double value = measurements[i];
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                throw new IllegalArgumentException("measurements must be finite and non-NaN");
            }
        }

        long now = clock.now();
        if (ttl > Long.MAX_VALUE - now) {
            throw new IllegalArgumentException("ttl would overflow the expiration timestamp");
        }

        long expiration = now + ttl;
        double[] copy = new double[measurements.length];
        for (int i = 0; i < measurements.length; i++) {
            copy[i] = measurements[i];
        }

        synchronized (lock) {
            ensureCapacity(batchCount + 1);
            batches[batchCount] = new Batch(copy, expiration);
            batchCount++;
        }
    }

    public int removeExpired() {
        long observationTime = clock.now();
        synchronized (lock) {
            int removed = 0;
            int writeIndex = 0;
            for (int i = 0; i < batchCount; i++) {
                Batch batch = batches[i];
                if (batch.expiration <= observationTime) {
                    removed += batch.values.length;
                } else {
                    if (writeIndex != i) {
                        batches[writeIndex] = batch;
                    }
                    writeIndex++;
                }
            }
            for (int i = writeIndex; i < batchCount; i++) {
                batches[i] = null;
            }
            batchCount = writeIndex;
            return removed;
        }
    }

    public Statistics statistics() {
        long observationTime = clock.now();
        final double[] snapshot;

        synchronized (lock) {
            purgeExpiredLocked(observationTime);
            snapshot = copyRemainingValuesLocked();
        }

        if (snapshot.length == 0) {
            return new Statistics(observationTime, 0L, Double.NaN, Double.NaN, Double.NaN);
        }

        quickSort(snapshot, 0, snapshot.length - 1);

        double mean = computeMean(snapshot);
        double median = computeMedian(snapshot);
        double variance = computePopulationVariance(snapshot, mean);

        return new Statistics(observationTime, snapshot.length, mean, median, variance);
    }

    private void ensureCapacity(int requiredCapacity) {
        if (requiredCapacity <= batches.length) {
            return;
        }

        int newSize = batches.length;
        while (newSize < requiredCapacity) {
            newSize = newSize + (newSize >> 1);
            if (newSize < requiredCapacity) {
                continue;
            }
            break;
        }

        Batch[] newBatches = new Batch[newSize];
        for (int i = 0; i < batchCount; i++) {
            newBatches[i] = batches[i];
        }
        batches = newBatches;
    }

    private void purgeExpiredLocked(long observationTime) {
        int writeIndex = 0;
        for (int i = 0; i < batchCount; i++) {
            Batch batch = batches[i];
            if (batch.expiration <= observationTime) {
                continue;
            }
            if (writeIndex != i) {
                batches[writeIndex] = batch;
            }
            writeIndex++;
        }
        for (int i = writeIndex; i < batchCount; i++) {
            batches[i] = null;
        }
        batchCount = writeIndex;
    }

    private double[] copyRemainingValuesLocked() {
        int totalSize = 0;
        for (int i = 0; i < batchCount; i++) {
            totalSize += batches[i].values.length;
        }

        double[] snapshot = new double[totalSize];
        int writeIndex = 0;
        for (int i = 0; i < batchCount; i++) {
            Batch batch = batches[i];
            int length = batch.values.length;
            System.arraycopy(batch.values, 0, snapshot, writeIndex, length);
            writeIndex += length;
        }
        return snapshot;
    }

    private static double computeMean(double[] values) {
        double mean = 0.0;
        double count = 0.0;
        for (int i = 0; i < values.length; i++) {
            double value = values[i];
            count += 1.0;
            double delta = value - mean;
            mean += delta / count;
        }
        return mean;
    }

    private static double computePopulationVariance(double[] values, double mean) {
        if (values.length == 0) {
            return Double.NaN;
        }
        double m2 = 0.0;
        for (int i = 0; i < values.length; i++) {
            double value = values[i];
            double delta = value - mean;
            m2 += delta * delta;
        }
        return m2 / values.length;
    }

    private static double computeMedian(double[] sortedValues) {
        int count = sortedValues.length;
        if (count == 0) {
            return Double.NaN;
        }
        int middle = count / 2;
        if ((count & 1) == 1) {
            return sortedValues[middle];
        }
        return (sortedValues[middle - 1] + sortedValues[middle]) / 2.0;
    }

    private static void quickSort(double[] values, int low, int high) {
        if (low >= high) {
            return;
        }
        if (high - low < 12) {
            insertionSort(values, low, high);
            return;
        }

        int pivotIndex = partition(values, low, high);
        quickSort(values, low, pivotIndex - 1);
        quickSort(values, pivotIndex + 1, high);
    }

    private static int partition(double[] values, int low, int high) {
        double pivot = values[(low + high) >>> 1];
        int left = low;
        int right = high;

        while (left <= right) {
            while (values[left] < pivot) {
                left++;
            }
            while (values[right] > pivot) {
                right--;
            }
            if (left <= right) {
                double temp = values[left];
                values[left] = values[right];
                values[right] = temp;
                left++;
                right--;
            }
        }
        return left;
    }

    private static void insertionSort(double[] values, int low, int high) {
        for (int i = low + 1; i <= high; i++) {
            double current = values[i];
            int j = i - 1;
            while (j >= low && values[j] > current) {
                values[j + 1] = values[j];
                j--;
            }
            values[j + 1] = current;
        }
    }
}

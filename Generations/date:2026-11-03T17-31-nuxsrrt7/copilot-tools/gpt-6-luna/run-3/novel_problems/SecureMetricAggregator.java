@SecureMetricAggregator.ThreadSafe
public final class SecureMetricAggregator {
    @interface ThreadSafe {}

    public interface Clock {
        long now();
    }

    public record Statistics(
            long observationTime,
            long count,
            double mean,
            double median,
            double variance) {
    }

    private static final class Batch {
        private final long expirationTime;
        private final double[] values;

        private Batch(long expirationTime, double[] values) {
            this.expirationTime = expirationTime;
            this.values = values;
        }
    }

    private final Clock clock;
    private final Object monitor = new Object();
    private Batch[] batches = new Batch[0];
    private int batchCount;

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

        double[] copiedValues = new double[measurements.length];
        for (int i = 0; i < measurements.length; i++) {
            double value = measurements[i];
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                throw new IllegalArgumentException("measurements must be finite");
            }
            copiedValues[i] = value;
        }

        synchronized (monitor) {
            long now = clock.now();
            if (now > Long.MAX_VALUE - ttl) {
                throw new IllegalArgumentException("expiration timestamp overflows");
            }
            long expirationTime = now + ttl;

            if (batchCount == batches.length) {
                if (batches.length == Integer.MAX_VALUE) {
                    throw new IllegalStateException("too many batches");
                }
                int newLength = batches.length == 0
                        ? 1
                        : (batches.length > Integer.MAX_VALUE / 2
                                ? Integer.MAX_VALUE
                                : batches.length * 2);
                Batch[] expanded = new Batch[newLength];
                for (int i = 0; i < batchCount; i++) {
                    expanded[i] = batches[i];
                }
                expanded[batchCount] = new Batch(expirationTime, copiedValues);
                batches = expanded;
                batchCount++;
            } else {
                batches[batchCount] = new Batch(expirationTime, copiedValues);
                batchCount++;
            }
        }
    }

    public long removeExpired() {
        synchronized (monitor) {
            return purgeExpired(clock.now());
        }
    }

    public Statistics statistics() {
        double[] snapshot;
        long observationTime;
        long count;

        synchronized (monitor) {
            observationTime = clock.now();
            purgeExpired(observationTime);

            long total = 0;
            for (int i = 0; i < batchCount; i++) {
                total += batches[i].values.length;
            }
            if (total > Integer.MAX_VALUE) {
                throw new IllegalStateException("too many measurements for a snapshot");
            }

            count = total;
            snapshot = new double[(int) total];
            int offset = 0;
            for (int i = 0; i < batchCount; i++) {
                double[] values = batches[i].values;
                for (int j = 0; j < values.length; j++) {
                    snapshot[offset++] = values[j];
                }
            }
        }

        if (count == 0) {
            double nan = 0.0 / 0.0;
            return new Statistics(observationTime, 0L, nan, nan, nan);
        }

        sort(snapshot);

        int length = snapshot.length;
        double median;
        if ((length & 1) != 0) {
            median = snapshot[length / 2];
        } else {
            median = midpoint(snapshot[length / 2 - 1], snapshot[length / 2]);
        }

        double scale = 0.0;
        for (int i = 0; i < length; i++) {
            double value = snapshot[i];
            double magnitude = value < 0.0 ? -value : value;
            if (magnitude > scale) {
                scale = magnitude;
            }
        }

        if (scale == 0.0) {
            return new Statistics(observationTime, count, 0.0, median, 0.0);
        }

        double sum = 0.0;
        double sumCompensation = 0.0;
        for (int i = 0; i < length; i++) {
            double normalized = snapshot[i] / scale;
            double adjusted = normalized - sumCompensation;
            double updated = sum + adjusted;
            sumCompensation = (updated - sum) - adjusted;
            sum = updated;
        }
        double normalizedMean = sum / count;
        double mean = normalizedMean * scale;

        double squaredDeviationSum = 0.0;
        double varianceCompensation = 0.0;
        for (int i = 0; i < length; i++) {
            double difference = snapshot[i] / scale - normalizedMean;
            double squaredDeviation = difference * difference;
            double adjusted = squaredDeviation - varianceCompensation;
            double updated = squaredDeviationSum + adjusted;
            varianceCompensation = (updated - squaredDeviationSum) - adjusted;
            squaredDeviationSum = updated;
        }
        double normalizedVariance = squaredDeviationSum / count;
        double variance = (normalizedVariance * scale) * scale;

        return new Statistics(observationTime, count, mean, median, variance);
    }

    private long purgeExpired(long observationTime) {
        long removedMeasurements = 0;
        int retained = 0;

        for (int i = 0; i < batchCount; i++) {
            Batch batch = batches[i];
            if (batch.expirationTime <= observationTime) {
                removedMeasurements += batch.values.length;
            } else {
                batches[retained++] = batch;
            }
        }

        for (int i = retained; i < batchCount; i++) {
            batches[i] = null;
        }
        batchCount = retained;
        return removedMeasurements;
    }

    private static double midpoint(double lower, double upper) {
        if ((lower < 0.0 && upper >= 0.0) || (lower >= 0.0 && upper < 0.0)) {
            return (lower + upper) / 2.0;
        }
        return lower + (upper - lower) / 2.0;
    }

    private static void sort(double[] values) {
        int length = values.length;
        for (int start = length / 2 - 1; start >= 0; start--) {
            siftDown(values, start, length - 1);
        }

        for (int end = length - 1; end > 0; end--) {
            double value = values[0];
            values[0] = values[end];
            values[end] = value;
            siftDown(values, 0, end - 1);
        }
    }

    private static void siftDown(double[] values, int root, int end) {
        while (root <= (end - 1) / 2) {
            int child = root * 2 + 1;
            if (child < end && values[child] < values[child + 1]) {
                child++;
            }
            if (values[root] >= values[child]) {
                return;
            }
            double value = values[root];
            values[root] = values[child];
            values[child] = value;
            root = child;
        }
    }
}

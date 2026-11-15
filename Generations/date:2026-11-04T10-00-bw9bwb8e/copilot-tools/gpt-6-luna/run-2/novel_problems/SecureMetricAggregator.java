@SecureMetricAggregator.ThreadSafe
public class SecureMetricAggregator {
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
        private final double[] values;
        private final long expiration;

        private Batch(double[] values, long expiration) {
            this.values = values;
            this.expiration = expiration;
        }
    }

    private final Clock clock;
    private final Object monitor = new Object();
    private Batch[] batches = new Batch[8];
    private int batchCount;

    public SecureMetricAggregator(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.clock = clock;
    }

    public void put(double[] measurements, long ttl) {
        if (measurements == null || measurements.length == 0) {
            throw new IllegalArgumentException("measurements must not be null or empty");
        }
        if (ttl <= 0) {
            throw new IllegalArgumentException("ttl must be positive");
        }

        double[] copied = measurements.clone();
        for (double value : copied) {
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                throw new IllegalArgumentException("measurements must be finite");
            }
        }

        synchronized (monitor) {
            long now = clock.now();
            if (ttl > Long.MAX_VALUE - now) {
                throw new ArithmeticException("expiration timestamp overflow");
            }
            long expiration = now + ttl;

            if (batchCount == batches.length) {
                if (batches.length >= Integer.MAX_VALUE / 2) {
                    throw new IllegalStateException("too many batches");
                }
                Batch[] expanded = new Batch[batches.length * 2];
                System.arraycopy(batches, 0, expanded, 0, batchCount);
                batches = expanded;
            }
            batches[batchCount++] = new Batch(copied, expiration);
        }
    }

    public long removeExpired() {
        synchronized (monitor) {
            long now = clock.now();
            return purgeExpired(now);
        }
    }

    public Statistics statistics() {
        final long observationTime;
        final double[] snapshot;

        synchronized (monitor) {
            observationTime = clock.now();
            purgeExpired(observationTime);

            int valueCount = 0;
            for (int i = 0; i < batchCount; i++) {
                int length = batches[i].values.length;
                if (length > Integer.MAX_VALUE - valueCount) {
                    throw new IllegalStateException("too many measurements to snapshot");
                }
                valueCount += length;
            }

            snapshot = new double[valueCount];
            int offset = 0;
            for (int i = 0; i < batchCount; i++) {
                double[] values = batches[i].values;
                System.arraycopy(values, 0, snapshot, offset, values.length);
                offset += values.length;
            }
        }

        if (snapshot.length == 0) {
            return new Statistics(observationTime, 0, Double.NaN, Double.NaN, Double.NaN);
        }

        double scale = 0.0;
        for (double value : snapshot) {
            double absolute = Math.abs(value);
            if (absolute > scale) {
                scale = absolute;
            }
        }

        double mean;
        double variance;
        if (scale == 0.0) {
            mean = 0.0;
            variance = 0.0;
        } else {
            double sum = 0.0;
            double compensation = 0.0;
            for (double value : snapshot) {
                double adjusted = value / scale - compensation;
                double next = sum + adjusted;
                compensation = (next - sum) - adjusted;
                sum = next;
            }
            mean = (sum / snapshot.length) * scale;

            double runningMean = 0.0;
            double sumSquaredDifferences = 0.0;
            long seen = 0;
            for (double value : snapshot) {
                double normalized = value / scale;
                seen++;
                double delta = normalized - runningMean;
                runningMean += delta / seen;
                double deltaAfterUpdate = normalized - runningMean;
                sumSquaredDifferences += delta * deltaAfterUpdate;
            }
            double scaledVariance = sumSquaredDifferences / snapshot.length;
            variance = (scaledVariance * scale) * scale;
        }

        sort(snapshot);
        int middle = snapshot.length / 2;
        double median;
        if ((snapshot.length & 1) != 0) {
            median = snapshot[middle];
        } else {
            double lower = snapshot[middle - 1];
            double upper = snapshot[middle];
            if ((lower < 0.0) != (upper < 0.0)) {
                median = lower / 2.0 + upper / 2.0;
            } else {
                median = lower + (upper - lower) / 2.0;
            }
        }

        return new Statistics(observationTime, snapshot.length, mean, median, variance);
    }

    private long purgeExpired(long observationTime) {
        int retained = 0;
        long removedMeasurements = 0;

        for (int i = 0; i < batchCount; i++) {
            Batch batch = batches[i];
            if (batch.expiration <= observationTime) {
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

    private static void sort(double[] values) {
        for (int start = values.length / 2 - 1; start >= 0; start--) {
            siftDown(values, start, values.length);
        }

        for (int end = values.length - 1; end > 0; end--) {
            double value = values[0];
            values[0] = values[end];
            values[end] = value;
            siftDown(values, 0, end);
        }
    }

    private static void siftDown(double[] values, int root, int end) {
        while (root < end / 2) {
            int child = root * 2 + 1;
            if (child + 1 < end && values[child] < values[child + 1]) {
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

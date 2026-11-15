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
        public double populationVariance() {
            return variance;
        }
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
    private long measurementCount;

    public SecureMetricAggregator(Clock clock) {
        if (clock == null) {
            throw new NullPointerException("clock");
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

        double[] copy = new double[measurements.length];
        for (int i = 0; i < measurements.length; i++) {
            double value = measurements[i];
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                throw new IllegalArgumentException("measurements must be finite");
            }
            copy[i] = value;
        }

        long now = clock.now();
        if (now < 0) {
            throw new IllegalStateException("clock returned a negative time");
        }
        if (now > Long.MAX_VALUE - ttl) {
            throw new IllegalArgumentException("expiration timestamp overflows");
        }
        long expiration = now + ttl;
        Batch batch = new Batch(copy, expiration);

        synchronized (monitor) {
            if (measurementCount > Long.MAX_VALUE - copy.length) {
                throw new IllegalStateException("measurement count overflows");
            }
            if (batchCount == batches.length) {
                if (batches.length > Integer.MAX_VALUE / 2) {
                    throw new IllegalStateException("too many batches");
                }
                Batch[] expanded = new Batch[batches.length * 2];
                for (int i = 0; i < batchCount; i++) {
                    expanded[i] = batches[i];
                }
                batches = expanded;
            }
            batches[batchCount++] = batch;
            measurementCount += copy.length;
        }
    }

    public long removeExpired() {
        long now = clock.now();
        if (now < 0) {
            throw new IllegalStateException("clock returned a negative time");
        }

        synchronized (monitor) {
            return purgeExpired(now);
        }
    }

    public Statistics statistics() {
        long observationTime = clock.now();
        if (observationTime < 0) {
            throw new IllegalStateException("clock returned a negative time");
        }

        double[] snapshot;
        synchronized (monitor) {
            purgeExpired(observationTime);
            if (measurementCount > Integer.MAX_VALUE) {
                throw new IllegalStateException("too many measurements for a snapshot");
            }

            snapshot = new double[(int) measurementCount];
            int offset = 0;
            for (int i = 0; i < batchCount; i++) {
                double[] values = batches[i].values;
                for (int j = 0; j < values.length; j++) {
                    snapshot[offset++] = values[j];
                }
            }
        }

        if (snapshot.length == 0) {
            return new Statistics(
                    observationTime, 0, Double.NaN, Double.NaN, Double.NaN);
        }

        double mean = calculateMean(snapshot);
        double variance = calculatePopulationVariance(snapshot, mean);
        sort(snapshot);
        double median;
        int middle = snapshot.length / 2;
        if ((snapshot.length & 1) != 0) {
            median = snapshot[middle];
        } else {
            median = midpoint(snapshot[middle - 1], snapshot[middle]);
        }

        return new Statistics(observationTime, snapshot.length, mean, median, variance);
    }

    private long purgeExpired(long now) {
        long removed = 0;
        int kept = 0;

        for (int i = 0; i < batchCount; i++) {
            Batch batch = batches[i];
            if (batch.expiration <= now) {
                removed += batch.values.length;
            } else {
                batches[kept++] = batch;
            }
        }

        for (int i = kept; i < batchCount; i++) {
            batches[i] = null;
        }
        batchCount = kept;
        measurementCount -= removed;
        return removed;
    }

    private static double calculateMean(double[] values) {
        double scale = 0.0;
        for (int i = 0; i < values.length; i++) {
            double absolute = Math.abs(values[i]);
            if (absolute > scale) {
                scale = absolute;
            }
        }
        if (scale == 0.0) {
            return 0.0;
        }

        double sum = 0.0;
        double compensation = 0.0;
        for (int i = 0; i < values.length; i++) {
            double normalized = values[i] / scale;
            double adjusted = normalized - compensation;
            double next = sum + adjusted;
            compensation = (next - sum) - adjusted;
            sum = next;
        }
        return (sum / values.length) * scale;
    }

    private static double calculatePopulationVariance(double[] values, double mean) {
        double scale = 0.0;
        for (int i = 0; i < values.length; i++) {
            double absolute = Math.abs(values[i]);
            if (absolute > scale) {
                scale = absolute;
            }
        }
        if (scale == 0.0) {
            return 0.0;
        }

        double normalizedMean = mean / scale;
        double sumSquares = 0.0;
        double compensation = 0.0;
        for (int i = 0; i < values.length; i++) {
            double difference = (values[i] / scale) - normalizedMean;
            double square = difference * difference;
            double adjusted = square - compensation;
            double next = sumSquares + adjusted;
            compensation = (next - sumSquares) - adjusted;
            sumSquares = next;
        }

        double normalizedVariance = sumSquares / values.length;
        return (normalizedVariance * scale) * scale;
    }

    private static double midpoint(double first, double second) {
        if ((first >= 0.0 && second >= 0.0)
                || (first <= 0.0 && second <= 0.0)) {
            return first + (second - first) / 2.0;
        }
        return first / 2.0 + second / 2.0;
    }

    private static void sort(double[] values) {
        int length = values.length;
        for (int start = length / 2 - 1; start >= 0; start--) {
            siftDown(values, start, length);
        }
        for (int end = length - 1; end > 0; end--) {
            double temporary = values[0];
            values[0] = values[end];
            values[end] = temporary;
            siftDown(values, 0, end);
        }
    }

    private static void siftDown(double[] values, int root, int length) {
        while (root * 2L + 1 < length) {
            int child = root * 2 + 1;
            int right = child + 1;
            if (right < length && Double.compare(values[right], values[child]) > 0) {
                child = right;
            }
            if (Double.compare(values[root], values[child]) >= 0) {
                return;
            }
            double temporary = values[root];
            values[root] = values[child];
            values[child] = temporary;
            root = child;
        }
    }
}

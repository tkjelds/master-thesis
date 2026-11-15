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
            double populationVariance) {
    }

    private static final class Batch {
        private final double[] values;
        private final long expiration;
        private Batch next;

        private Batch(double[] values, long expiration) {
            this.values = values;
            this.expiration = expiration;
        }
    }

    private final Clock clock;
    private final Object monitor = new Object();
    private Batch head;

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

        double[] copy = new double[measurements.length];
        for (int i = 0; i < measurements.length; i++) {
            double value = measurements[i];
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                throw new IllegalArgumentException("measurements must be finite");
            }
            copy[i] = value;
        }

        long now = clock.now();
        if (now > Long.MAX_VALUE - ttl) {
            throw new IllegalArgumentException("expiration timestamp overflows");
        }
        Batch batch = new Batch(copy, now + ttl);

        synchronized (monitor) {
            batch.next = head;
            head = batch;
        }
    }

    public long removeExpired() {
        long observationTime = clock.now();
        synchronized (monitor) {
            return purgeExpired(observationTime);
        }
    }

    public Statistics statistics() {
        long observationTime = clock.now();
        double[] snapshot;

        synchronized (monitor) {
            purgeExpired(observationTime);

            long total = 0;
            for (Batch batch = head; batch != null; batch = batch.next) {
                total += batch.values.length;
                if (total > Integer.MAX_VALUE) {
                    throw new IllegalStateException("too many measurements for a Java array");
                }
            }

            snapshot = new double[(int) total];
            int index = 0;
            for (Batch batch = head; batch != null; batch = batch.next) {
                for (double value : batch.values) {
                    snapshot[index++] = value;
                }
            }
        }

        if (snapshot.length == 0) {
            return new Statistics(
                    observationTime, 0, Double.NaN, Double.NaN, Double.NaN);
        }

        sort(snapshot);

        double maxAbs = 0.0;
        for (double value : snapshot) {
            double abs = Math.abs(value);
            if (abs > maxAbs) {
                maxAbs = abs;
            }
        }

        double mean;
        double variance;
        if (maxAbs == 0.0) {
            mean = 0.0;
            variance = 0.0;
        } else {
            double normalizedSum = 0.0;
            double sumCompensation = 0.0;
            for (double value : snapshot) {
                double adjusted = value / maxAbs - sumCompensation;
                double nextSum = normalizedSum + adjusted;
                sumCompensation = (nextSum - normalizedSum) - adjusted;
                normalizedSum = nextSum;
            }

            double normalizedMean = normalizedSum / snapshot.length;
            mean = normalizedMean * maxAbs;

            double squaredDeviationSum = 0.0;
            double varianceCompensation = 0.0;
            for (double value : snapshot) {
                double deviation = value / maxAbs - normalizedMean;
                double squaredDeviation = deviation * deviation;
                double adjusted = squaredDeviation - varianceCompensation;
                double nextSum = squaredDeviationSum + adjusted;
                varianceCompensation = (nextSum - squaredDeviationSum) - adjusted;
                squaredDeviationSum = nextSum;
            }

            double normalizedVariance = squaredDeviationSum / snapshot.length;
            variance = normalizedVariance * maxAbs * maxAbs;
        }

        int middle = snapshot.length / 2;
        double median;
        if ((snapshot.length & 1) == 1) {
            median = snapshot[middle];
        } else {
            median = snapshot[middle - 1] / 2.0 + snapshot[middle] / 2.0;
        }

        return new Statistics(
                observationTime, snapshot.length, mean, median, variance);
    }

    private long purgeExpired(long observationTime) {
        long removed = 0;
        Batch previous = null;
        Batch current = head;

        while (current != null) {
            if (current.expiration <= observationTime) {
                removed += current.values.length;
                if (previous == null) {
                    head = current.next;
                } else {
                    previous.next = current.next;
                }
                current = current.next;
            } else {
                previous = current;
                current = current.next;
            }
        }

        return removed;
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

    private static void siftDown(double[] values, int start, int end) {
        int root = start;
        while (root <= (end - 1) / 2 && root * 2 + 1 <= end) {
            int child = root * 2 + 1;
            if (child + 1 <= end && values[child] < values[child + 1]) {
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

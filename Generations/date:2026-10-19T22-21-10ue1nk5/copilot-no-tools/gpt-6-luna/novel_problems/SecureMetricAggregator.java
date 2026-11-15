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

        double[] copy = measurements.clone();
        for (double value : copy) {
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                throw new IllegalArgumentException("measurements must be finite");
            }
        }

        synchronized (monitor) {
            long now = clock.now();
            if (now > Long.MAX_VALUE - ttl) {
                throw new IllegalArgumentException("expiration timestamp overflows");
            }

            Batch batch = new Batch(copy, now + ttl);
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

            long valueCount = 0;
            for (Batch batch = head; batch != null; batch = batch.next) {
                valueCount += batch.values.length;
                if (valueCount > Integer.MAX_VALUE) {
                    throw new IllegalStateException("too many measurements to snapshot");
                }
            }

            snapshot = new double[(int) valueCount];
            int offset = 0;
            for (Batch batch = head; batch != null; batch = batch.next) {
                for (double value : batch.values) {
                    snapshot[offset++] = value;
                }
            }
        }

        if (snapshot.length == 0) {
            return new Statistics(
                    observationTime, 0, Double.NaN, Double.NaN, Double.NaN);
        }

        heapSort(snapshot);

        int count = snapshot.length;
        double median;
        if ((count & 1) == 1) {
            median = snapshot[count / 2];
        } else {
            median = average(snapshot[count / 2 - 1], snapshot[count / 2]);
        }

        double scale = 0.0;
        for (double value : snapshot) {
            double magnitude = value < 0.0 ? -value : value;
            if (magnitude > scale) {
                scale = magnitude;
            }
        }

        double mean;
        double variance;
        if (scale == 0.0) {
            mean = 0.0;
            variance = 0.0;
        } else {
            double normalizedSum = 0.0;
            double sumCompensation = 0.0;
            for (double value : snapshot) {
                double adjusted = value / scale - sumCompensation;
                double nextSum = normalizedSum + adjusted;
                sumCompensation = (nextSum - normalizedSum) - adjusted;
                normalizedSum = nextSum;
            }

            double normalizedMean = normalizedSum / count;
            if (normalizedMean > 1.0) {
                normalizedMean = 1.0;
            } else if (normalizedMean < -1.0) {
                normalizedMean = -1.0;
            }
            mean = normalizedMean * scale;

            double squaredDeviationSum = 0.0;
            double varianceCompensation = 0.0;
            for (double value : snapshot) {
                double deviation = value / scale - normalizedMean;
                double term = deviation * deviation;
                double adjusted = term - varianceCompensation;
                double nextSum = squaredDeviationSum + adjusted;
                varianceCompensation = (nextSum - squaredDeviationSum) - adjusted;
                squaredDeviationSum = nextSum;
            }

            double normalizedVariance = squaredDeviationSum / count;
            if (normalizedVariance > 1.0) {
                normalizedVariance = 1.0;
            }
            double standardDeviation = scale * Math.sqrt(normalizedVariance);
            variance = standardDeviation * standardDeviation;
        }

        return new Statistics(observationTime, count, mean, median, variance);
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
            } else {
                previous = current;
            }
            current = current.next;
        }

        return removed;
    }

    private static double average(double first, double second) {
        if ((first < 0.0) == (second < 0.0)) {
            return first + (second - first) * 0.5;
        }
        return first * 0.5 + second * 0.5;
    }

    private static void heapSort(double[] values) {
        int length = values.length;
        for (int start = length / 2 - 1; start >= 0; start--) {
            siftDown(values, start, length);
        }

        for (int end = length - 1; end > 0; end--) {
            double value = values[0];
            values[0] = values[end];
            values[end] = value;
            siftDown(values, 0, end);
        }
    }

    private static void siftDown(double[] values, int root, int size) {
        while (root < size / 2) {
            int child = root * 2 + 1;
            if (child + 1 < size && values[child] < values[child + 1]) {
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

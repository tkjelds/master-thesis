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

    private static final double MAX_FINITE = 0x1.fffffffffffffp1023;
    private static final double NAN = 0.0 / 0.0;
    private static final long MAX_TIMESTAMP = 0x7fffffffffffffffL;

    private static final class Monitor {
    }

    private static final class Batch {
        final double[] values;
        final long expiration;
        Batch next;

        Batch(double[] values, long expiration, Batch next) {
            this.values = values;
            this.expiration = expiration;
            this.next = next;
        }
    }

    private static final class CompensatedSum {
        private double sum;
        private double correction;

        void add(double value) {
            double next = sum + value;
            if (absolute(sum) >= absolute(value)) {
                correction += (sum - next) + value;
            } else {
                correction += (value - next) + sum;
            }
            sum = next;
        }

        double value() {
            return sum + correction;
        }
    }

    private final Monitor monitor = new Monitor();
    private final Clock clock;
    private Batch head;
    private int measurementCount;

    public SecureMetricAggregator(Clock clock) {
        if (clock == null) {
            throw new NullPointerException("clock");
        }
        this.clock = clock;
    }

    /**
     * Stores a defensive copy of one batch.
     *
     * Storage is bounded by the maximum Java array length representation:
     * Integer.MAX_VALUE measurements. Capacity exhaustion fails immediately
     * with IllegalStateException rather than blocking.
     */
    public void put(double[] measurements, long ttl) {
        if (measurements == null) {
            throw new NullPointerException("measurements");
        }
        if (measurements.length == 0) {
            throw new IllegalArgumentException("Measurements must not be empty");
        }
        if (ttl <= 0) {
            throw new IllegalArgumentException("TTL must be positive");
        }

        double[] copy = new double[measurements.length];
        for (int i = 0; i < measurements.length; i++) {
            double value = measurements[i];
            if (value != value || value > MAX_FINITE || value < -MAX_FINITE) {
                throw new IllegalArgumentException("Measurements must be finite");
            }
            copy[i] = value;
        }

        synchronized (monitor) {
            long now = clock.now();
            if (ttl > MAX_TIMESTAMP - now) {
                throw new IllegalArgumentException("Expiration timestamp overflow");
            }
            if (copy.length > 0x7fffffff - measurementCount) {
                throw new IllegalStateException("Measurement capacity exceeded");
            }

            Batch batch = new Batch(copy, now + ttl, head);
            head = batch;
            measurementCount += copy.length;
        }
    }

    public long removeExpired() {
        synchronized (monitor) {
            long observationTime = clock.now();
            return purgeExpired(observationTime);
        }
    }

    public Statistics statistics() {
        final long observationTime;
        final double[] snapshot;

        synchronized (monitor) {
            observationTime = clock.now();
            purgeExpired(observationTime);

            snapshot = new double[measurementCount];
            int destination = 0;
            for (Batch batch = head; batch != null; batch = batch.next) {
                for (int i = 0; i < batch.values.length; i++) {
                    snapshot[destination++] = batch.values[i];
                }
            }
        }

        int count = snapshot.length;
        if (count == 0) {
            return new Statistics(observationTime, 0, NAN, NAN, NAN);
        }

        double scale = 0.0;
        for (int i = 0; i < count; i++) {
            double magnitude = absolute(snapshot[i]);
            if (magnitude > scale) {
                scale = magnitude;
            }
        }

        double mean = 0.0;
        double variance = 0.0;

        if (scale != 0.0) {
            // Scaling prevents overflow in sums and squared deviations.
            CompensatedSum normalizedSum = new CompensatedSum();
            for (int i = 0; i < count; i++) {
                normalizedSum.add(snapshot[i] / scale);
            }

            double normalizedMean = normalizedSum.value() / count;
            if (normalizedMean > 1.0) {
                normalizedMean = 1.0;
            } else if (normalizedMean < -1.0) {
                normalizedMean = -1.0;
            }
            mean = normalizedMean * scale;

            CompensatedSum squaredDeviations = new CompensatedSum();
            for (int i = 0; i < count; i++) {
                double deviation = snapshot[i] / scale - normalizedMean;
                squaredDeviations.add(deviation * deviation);
            }

            double normalizedVariance = squaredDeviations.value() / count;
            // Values normalized to [-1, 1] have population variance at most 1.
            if (normalizedVariance > 1.0) {
                normalizedVariance = 1.0;
            }
            variance = (normalizedVariance * scale) * scale;
        }

        heapSort(snapshot);

        int middle = count / 2;
        double median;
        if ((count & 1) != 0) {
            median = snapshot[middle];
        } else {
            double lower = snapshot[middle - 1];
            double upper = snapshot[middle];
            if (lower <= 0.0 && upper >= 0.0) {
                median = (lower + upper) / 2.0;
            } else {
                median = lower + (upper - lower) / 2.0;
            }
        }

        return new Statistics(observationTime, count, mean, median, variance);
    }

    // Called only while holding monitor.
    private long purgeExpired(long observationTime) {
        long removed = 0;
        Batch previous = null;
        Batch current = head;

        while (current != null) {
            Batch next = current.next;
            if (current.expiration <= observationTime) {
                if (previous == null) {
                    head = next;
                } else {
                    previous.next = next;
                }
                measurementCount -= current.values.length;
                removed += current.values.length;
            } else {
                previous = current;
            }
            current = next;
        }
        return removed;
    }

    private static double absolute(double value) {
        return value < 0.0 ? -value : value;
    }

    private static void heapSort(double[] values) {
        int length = values.length;
        for (int root = length / 2 - 1; root >= 0; root--) {
            siftDown(values, root, length);
        }

        for (int end = length - 1; end > 0; end--) {
            double temporary = values[0];
            values[0] = values[end];
            values[end] = temporary;
            siftDown(values, 0, end);
        }
    }

    private static void siftDown(double[] values, int root, int length) {
        while (root < length / 2) {
            int child = root * 2 + 1;
            if (child + 1 < length && values[child] < values[child + 1]) {
                child++;
            }
            if (values[root] >= values[child]) {
                return;
            }

            double temporary = values[root];
            values[root] = values[child];
            values[child] = temporary;
            root = child;
        }
    }
}

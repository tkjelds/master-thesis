@SecureMetricAggregator.ThreadSafe
public final class SecureMetricAggregator {
    @interface ThreadSafe {}

    public interface Clock {
        long now();
    }

    public static record Statistics(
            long observationTime,
            long count,
            double mean,
            double median,
            double variance) {
    }

    private static final double MAX_FINITE = 1.7976931348623157E308;
    private static final double NAN = 0.0 / 0.0;

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

    private final Clock clock;
    private final Object monitor = new Object();
    private Batch batches;
    private int valueCount;

    public SecureMetricAggregator(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.clock = clock;
    }

    /*
     * Operations do not block waiting for capacity. A put that would make the
     * retained measurement count exceed the array index range is rejected.
     */
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
            if (value != value || value > MAX_FINITE || value < -MAX_FINITE) {
                throw new IllegalArgumentException("measurements must be finite");
            }
            copy[i] = value;
        }

        synchronized (monitor) {
            long now = clock.now();
            if (ttl > 9223372036854775807L - now) {
                throw new IllegalArgumentException("expiration timestamp overflow");
            }
            if (copy.length > 2147483647 - valueCount) {
                throw new IllegalStateException("measurement capacity exceeded");
            }

            Batch batch = new Batch(copy, now + ttl, batches);
            batches = batch;
            valueCount += copy.length;
        }
    }

    public long removeExpired() {
        synchronized (monitor) {
            return purgeExpired(clock.now());
        }
    }

    public Statistics statistics() {
        long observationTime;
        double[] snapshot;

        synchronized (monitor) {
            observationTime = clock.now();
            purgeExpired(observationTime);

            snapshot = new double[valueCount];
            int index = 0;
            for (Batch batch = batches; batch != null; batch = batch.next) {
                for (int i = 0; i < batch.values.length; i++) {
                    snapshot[index++] = batch.values[i];
                }
            }
        }

        int count = snapshot.length;
        if (count == 0) {
            return new Statistics(observationTime, 0, NAN, NAN, NAN);
        }

        sort(snapshot);

        double median;
        int middle = count / 2;
        if ((count & 1) != 0) {
            median = snapshot[middle];
        } else {
            double lower = snapshot[middle - 1];
            double upper = snapshot[middle];
            double sum = lower + upper;
            if (sum > MAX_FINITE || sum < -MAX_FINITE) {
                median = lower * 0.5 + upper * 0.5;
            } else {
                median = sum * 0.5;
            }
        }

        double scale = 0.0;
        for (int i = 0; i < count; i++) {
            double magnitude = absolute(snapshot[i]);
            if (magnitude > scale) {
                scale = magnitude;
            }
        }
        if (scale == 0.0) {
            return new Statistics(observationTime, count, 0.0, median, 0.0);
        }

        // Compensated summation of scaled values avoids overflow in the mean.
        double sum = 0.0;
        double correction = 0.0;
        for (int i = 0; i < count; i++) {
            double term = snapshot[i] / scale;
            double next = sum + term;
            if (absolute(sum) >= absolute(term)) {
                correction += (sum - next) + term;
            } else {
                correction += (term - next) + sum;
            }
            sum = next;
        }
        double scaledMean = (sum + correction) / count;
        if (scaledMean > 1.0) {
            scaledMean = 1.0;
        } else if (scaledMean < -1.0) {
            scaledMean = -1.0;
        }
        double mean = scaledMean * scale;

        /*
         * Center differences on an actual value before scaling. This preserves
         * small differences between large, nearby measurements.
         */
        double anchor = snapshot[0];
        sum = 0.0;
        correction = 0.0;
        for (int i = 0; i < count; i++) {
            double term = scaledDifference(snapshot[i], anchor, scale);
            double next = sum + term;
            if (absolute(sum) >= absolute(term)) {
                correction += (sum - next) + term;
            } else {
                correction += (term - next) + sum;
            }
            sum = next;
        }
        double centeredMean = (sum + correction) / count;

        sum = 0.0;
        correction = 0.0;
        for (int i = 0; i < count; i++) {
            double deviation = scaledDifference(snapshot[i], anchor, scale) - centeredMean;
            double term = deviation * deviation;
            double next = sum + term;
            if (absolute(sum) >= absolute(term)) {
                correction += (sum - next) + term;
            } else {
                correction += (term - next) + sum;
            }
            sum = next;
        }
        double scaledVariance = (sum + correction) / count;
        double variance = (scaledVariance * scale) * scale;

        return new Statistics(observationTime, count, mean, median, variance);
    }

    // Called only while holding monitor.
    private long purgeExpired(long now) {
        long removed = 0;
        Batch previous = null;
        Batch current = batches;

        while (current != null) {
            Batch next = current.next;
            if (current.expiration <= now) {
                if (previous == null) {
                    batches = next;
                } else {
                    previous.next = next;
                }
                removed += current.values.length;
                valueCount -= current.values.length;
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

    private static double scaledDifference(double value, double anchor, double scale) {
        double difference = value - anchor;
        if (difference > MAX_FINITE || difference < -MAX_FINITE) {
            return value / scale - anchor / scale;
        }
        return difference / scale;
    }

    private static void sort(double[] values) {
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

    private static void siftDown(double[] values, int root, int size) {
        while (root < size / 2) {
            int child = root * 2 + 1;
            if (child + 1 < size && values[child] < values[child + 1]) {
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

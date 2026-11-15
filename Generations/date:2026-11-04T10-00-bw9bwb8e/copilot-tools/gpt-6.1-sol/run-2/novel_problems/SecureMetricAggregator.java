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

    private static final double NAN = 0.0 / 0.0;
    private static final double INFINITY = 1.0 / 0.0;

    private static final class Monitor {
    }

    private static final class Batch {
        final double[] values;
        final long expiration;
        Batch next;

        Batch(double[] values, long expiration) {
            this.values = values;
            this.expiration = expiration;
        }
    }

    private static final class Value {
        final double value;
        Value next;

        Value(double value) {
            this.value = value;
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
    private Batch first;
    private Batch last;
    private long count;

    public SecureMetricAggregator(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("Clock must not be null");
        }
        this.clock = clock;
    }

    /**
     * Inserts a defensive copy without waiting for capacity.
     * Timestamp or measurement-count overflow is rejected before mutation.
     */
    public void put(double[] measurements, long ttl) {
        if (measurements == null || measurements.length == 0) {
            throw new IllegalArgumentException("Measurements must not be null or empty");
        }
        if (ttl <= 0) {
            throw new IllegalArgumentException("TTL must be positive");
        }

        double[] copy = new double[measurements.length];
        for (int i = 0; i < measurements.length; i++) {
            double value = measurements[i];
            if (value != value || value == INFINITY || value == -INFINITY) {
                throw new IllegalArgumentException("Measurements must be finite");
            }
            copy[i] = value;
        }

        synchronized (monitor) {
            long now = clock.now();
            if (now > 9223372036854775807L - ttl) {
                throw new IllegalArgumentException("Expiration timestamp overflow");
            }
            if (count > 9223372036854775807L - copy.length) {
                throw new IllegalStateException("Measurement count overflow");
            }

            Batch batch = new Batch(copy, now + ttl);
            if (last == null) {
                first = batch;
            } else {
                last.next = batch;
            }
            last = batch;
            count += copy.length;
        }
    }

    public long removeExpired() {
        synchronized (monitor) {
            return purgeExpired(clock.now());
        }
    }

    public Statistics statistics() {
        long observationTime;
        long snapshotCount;
        Value snapshot = null;
        Value snapshotTail = null;

        synchronized (monitor) {
            observationTime = clock.now();
            purgeExpired(observationTime);
            snapshotCount = count;

            // A linked snapshot avoids imposing a single-array size limit.
            for (Batch batch = first; batch != null; batch = batch.next) {
                for (int i = 0; i < batch.values.length; i++) {
                    Value value = new Value(batch.values[i]);
                    if (snapshotTail == null) {
                        snapshot = value;
                    } else {
                        snapshotTail.next = value;
                    }
                    snapshotTail = value;
                }
            }
        }

        if (snapshotCount == 0) {
            return new Statistics(observationTime, 0, NAN, NAN, NAN);
        }

        double scale = 0.0;
        for (Value node = snapshot; node != null; node = node.next) {
            double magnitude = absolute(node.value);
            if (magnitude > scale) {
                scale = magnitude;
            }
        }

        if (scale == 0.0) {
            return new Statistics(observationTime, snapshotCount, 0.0, 0.0, 0.0);
        }

        // Scaling prevents intermediate sums and squared differences overflowing.
        CompensatedSum normalizedSum = new CompensatedSum();
        for (Value node = snapshot; node != null; node = node.next) {
            normalizedSum.add(node.value / scale);
        }

        double normalizedMean = normalizedSum.value() / (double) snapshotCount;
        if (normalizedMean > 1.0) {
            normalizedMean = 1.0;
        } else if (normalizedMean < -1.0) {
            normalizedMean = -1.0;
        }
        double mean = normalizedMean * scale;

        CompensatedSum squaredDeviations = new CompensatedSum();
        CompensatedSum deviations = new CompensatedSum();
        for (Value node = snapshot; node != null; node = node.next) {
            double deviation = node.value / scale - normalizedMean;
            squaredDeviations.add(deviation * deviation);
            deviations.add(deviation);
        }

        double averageDeviation = deviations.value() / (double) snapshotCount;
        double normalizedVariance =
                squaredDeviations.value() / (double) snapshotCount
                - averageDeviation * averageDeviation;
        if (normalizedVariance < 0.0) {
            normalizedVariance = 0.0;
        }
        double variance = (normalizedVariance * scale) * scale;

        Value sorted = sort(snapshot, snapshotCount);
        long middleIndex = snapshotCount / 2;
        Value middle = sorted;
        Value previous = null;
        for (long i = 0; i < middleIndex; i++) {
            previous = middle;
            middle = middle.next;
        }

        double median = (snapshotCount & 1L) != 0
                ? middle.value
                : midpoint(previous.value, middle.value);

        return new Statistics(
                observationTime, snapshotCount, mean, median, variance);
    }

    // Called only while holding monitor.
    private long purgeExpired(long observationTime) {
        long removed = 0;
        Batch previous = null;
        Batch current = first;

        while (current != null) {
            Batch next = current.next;
            if (current.expiration <= observationTime) {
                removed += current.values.length;
                if (previous == null) {
                    first = next;
                } else {
                    previous.next = next;
                }
                if (last == current) {
                    last = previous;
                }
            } else {
                previous = current;
            }
            current = next;
        }

        count -= removed;
        return removed;
    }

    private static Value sort(Value head, long length) {
        if (length < 2) {
            return head;
        }

        long leftLength = length / 2;
        Value leftTail = head;
        for (long i = 1; i < leftLength; i++) {
            leftTail = leftTail.next;
        }

        Value right = leftTail.next;
        leftTail.next = null;
        Value left = sort(head, leftLength);
        right = sort(right, length - leftLength);
        return merge(left, right);
    }

    private static Value merge(Value left, Value right) {
        Value head;
        if (left.value <= right.value) {
            head = left;
            left = left.next;
        } else {
            head = right;
            right = right.next;
        }

        Value tail = head;
        while (left != null && right != null) {
            if (left.value <= right.value) {
                tail.next = left;
                left = left.next;
            } else {
                tail.next = right;
                right = right.next;
            }
            tail = tail.next;
        }
        tail.next = left != null ? left : right;
        return head;
    }

    private static double midpoint(double lower, double upper) {
        if ((lower < 0.0) != (upper < 0.0)) {
            return (lower + upper) * 0.5;
        }
        return lower + (upper - lower) * 0.5;
    }

    private static double absolute(double value) {
        return value < 0.0 ? -value : value;
    }
}

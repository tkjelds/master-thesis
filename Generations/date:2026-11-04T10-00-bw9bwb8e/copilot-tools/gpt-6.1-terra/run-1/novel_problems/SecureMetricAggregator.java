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

    private static final double MAX_FINITE = 1.7976931348623157e308;
    private static final double NAN = 0.0 / 0.0;

    private final Object monitor = new Object();
    private final Clock clock;
    private Batch batches;
    private long measurementCount;

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

    private static final class SnapshotBatch {
        final double[] values;
        final SnapshotBatch next;

        SnapshotBatch(double[] values, SnapshotBatch next) {
            this.values = values;
            this.next = next;
        }
    }

    private static final class ValueNode {
        final double value;
        ValueNode next;

        ValueNode(double value, ValueNode next) {
            this.value = value;
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
            if (!isFinite(value)) {
                throw new IllegalArgumentException("measurements must be finite");
            }
            copy[i] = value;
        }

        synchronized (monitor) {
            long now = clock.now();
            if (ttl > Long.MAX_VALUE - now) {
                throw new IllegalArgumentException("expiration timestamp overflows");
            }
            if (copy.length > Long.MAX_VALUE - measurementCount) {
                throw new IllegalStateException("measurement count overflows");
            }

            batches = new Batch(copy, now + ttl, batches);
            measurementCount += copy.length;
        }
    }

    public long removeExpired() {
        synchronized (monitor) {
            return removeExpiredAt(clock.now());
        }
    }

    public Statistics statistics() {
        long observationTime;
        long count;
        SnapshotBatch snapshot = null;

        synchronized (monitor) {
            observationTime = clock.now();
            removeExpiredAt(observationTime);
            count = measurementCount;

            for (Batch batch = batches; batch != null; batch = batch.next) {
                double[] copy = new double[batch.values.length];
                for (int i = 0; i < copy.length; i++) {
                    copy[i] = batch.values[i];
                }
                snapshot = new SnapshotBatch(copy, snapshot);
            }
        }

        if (count == 0) {
            return new Statistics(observationTime, 0, NAN, NAN, NAN);
        }

        double scale = 0.0;
        double reference = snapshot.values[0];
        ValueNode values = null;

        for (SnapshotBatch batch = snapshot; batch != null; batch = batch.next) {
            for (double value : batch.values) {
                double magnitude = absolute(value);
                if (magnitude > scale) {
                    scale = magnitude;
                }
                values = new ValueNode(value, values);
            }
        }

        if (scale == 0.0) {
            return new Statistics(observationTime, count, 0.0, 0.0, 0.0);
        }

        double divisor = (double) count;
        CompensatedSum scaledSum = new CompensatedSum();
        CompensatedSum directMean = new CompensatedSum();
        CompensatedSum deltaSum = new CompensatedSum();
        boolean directMeanUsable = true;

        for (ValueNode node = values; node != null; node = node.next) {
            double value = node.value;
            scaledSum.add(value / scale);
            deltaSum.add(scaledDifference(value, reference, scale));

            if (directMeanUsable) {
                double term = value / divisor;
                if (term == 0.0 && value != 0.0) {
                    directMeanUsable = false;
                } else {
                    directMean.add(term);
                    if (!isFinite(directMean.value())) {
                        directMeanUsable = false;
                    }
                }
            }
        }

        double mean = directMeanUsable
                ? directMean.value()
                : (scaledSum.value() / divisor) * scale;

        double averageDelta = deltaSum.value() / divisor;
        CompensatedSum squaredDeviations = new CompensatedSum();
        for (ValueNode node = values; node != null; node = node.next) {
            double deviation = scaledDifference(node.value, reference, scale) - averageDelta;
            squaredDeviations.add(deviation * deviation);
        }
        double scaledVariance = squaredDeviations.value() / divisor;
        double variance = (scaledVariance * scale) * scale;

        values = sort(values, count);
        long middleIndex = count / 2;
        ValueNode middle = values;
        if ((count & 1L) == 0) {
            for (long i = 1; i < middleIndex; i++) {
                middle = middle.next;
            }
            return new Statistics(
                    observationTime, count, mean,
                    midpoint(middle.value, middle.next.value), variance);
        }

        for (long i = 0; i < middleIndex; i++) {
            middle = middle.next;
        }
        return new Statistics(observationTime, count, mean, middle.value, variance);
    }

    private long removeExpiredAt(long now) {
        long removed = 0;
        Batch previous = null;
        Batch current = batches;

        while (current != null) {
            if (current.expiration <= now) {
                removed += current.values.length;
                if (previous == null) {
                    batches = current.next;
                } else {
                    previous.next = current.next;
                }
            } else {
                previous = current;
            }
            current = current.next;
        }

        measurementCount -= removed;
        return removed;
    }

    private static boolean isFinite(double value) {
        return value == value && value <= MAX_FINITE && value >= -MAX_FINITE;
    }

    private static double absolute(double value) {
        return value < 0.0 ? -value : value;
    }

    private static double scaledDifference(double value, double reference, double scale) {
        double difference = value - reference;
        if (isFinite(difference)) {
            return difference / scale;
        }
        return value / scale - reference / scale;
    }

    private static double midpoint(double lower, double upper) {
        if ((lower <= 0.0 && upper >= 0.0)
                || (absolute(lower) <= MAX_FINITE / 2.0
                && absolute(upper) <= MAX_FINITE / 2.0)) {
            return (lower + upper) / 2.0;
        }
        return lower / 2.0 + upper / 2.0;
    }

    private static ValueNode sort(ValueNode head, long count) {
        long width = 1;

        while (width < count) {
            ValueNode cursor = head;
            ValueNode sortedHead = null;
            ValueNode tail = null;

            while (cursor != null) {
                ValueNode left = cursor;
                ValueNode right = cursor;
                long leftRemaining = 0;

                while (leftRemaining < width && right != null) {
                    right = right.next;
                    leftRemaining++;
                }

                long rightRemaining = width;
                while (leftRemaining > 0 || (rightRemaining > 0 && right != null)) {
                    ValueNode chosen;
                    if (leftRemaining == 0) {
                        chosen = right;
                        right = right.next;
                        rightRemaining--;
                    } else if (rightRemaining == 0 || right == null
                            || left.value <= right.value) {
                        chosen = left;
                        left = left.next;
                        leftRemaining--;
                    } else {
                        chosen = right;
                        right = right.next;
                        rightRemaining--;
                    }

                    if (tail == null) {
                        sortedHead = chosen;
                    } else {
                        tail.next = chosen;
                    }
                    tail = chosen;
                }

                cursor = right;
            }

            tail.next = null;
            head = sortedHead;
            width = width > count / 2 ? count : width * 2;
        }

        return head;
    }
}

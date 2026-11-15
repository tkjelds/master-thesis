@SecureMetricAggregator.ThreadSafe
public final class SecureMetricAggregator {
    @interface ThreadSafe {}

    private static final double MAX_FINITE = 0x1.fffffffffffffp1023;
    private static final double NAN = 0.0 / 0.0;
    private static final long MAX_COUNT = 0x7fffffffffffffffL;

    public interface Clock {
        long now();
    }

    /** Variance is the population variance. */
    public record Statistics(
            long observationTime,
            long count,
            double mean,
            double median,
            double variance) {
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
        final double number;
        Value next;

        Value(double number) {
            this.number = number;
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

    private final Clock clock;
    private final byte[] monitor = new byte[0];
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
     * Storage grows as needed. Operations never wait for data or capacity.
     * Timestamp and count overflow are rejected before insertion.
     */
    public void put(double[] measurements, long ttl) {
        if (measurements == null || measurements.length == 0) {
            throw new IllegalArgumentException(
                    "Measurements must not be null or empty");
        }
        if (ttl <= 0) {
            throw new IllegalArgumentException("TTL must be positive");
        }

        double[] copy = new double[measurements.length];
        for (int i = 0; i < copy.length; i++) {
            double value = measurements[i];
            if (!isFinite(value)) {
                throw new IllegalArgumentException(
                        "Measurements must be finite");
            }
            copy[i] = value;
        }

        synchronized (monitor) {
            long now = clock.now();
            if (now > MAX_COUNT - ttl) {
                throw new IllegalArgumentException("Expiration timestamp overflow");
            }
            if (count > MAX_COUNT - copy.length) {
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
            long now = clock.now();
            return purgeExpired(now);
        }
    }

    public Statistics statistics() {
        final long observationTime;
        final long snapshotCount;
        Value snapshot = null;

        synchronized (monitor) {
            observationTime = clock.now();
            purgeExpired(observationTime);
            snapshotCount = count;

            Value tail = null;
            for (Batch batch = first; batch != null; batch = batch.next) {
                for (int i = 0; i < batch.values.length; i++) {
                    Value value = new Value(batch.values[i]);
                    if (tail == null) {
                        snapshot = value;
                    } else {
                        tail.next = value;
                    }
                    tail = value;
                }
            }
        }

        if (snapshotCount == 0) {
            return new Statistics(observationTime, 0, NAN, NAN, NAN);
        }

        // All subsequent work operates exclusively on detached values.
        snapshot = sort(snapshot, snapshotCount);

        double scale = 0.0;
        for (Value value = snapshot; value != null; value = value.next) {
            double magnitude = absolute(value.number);
            if (magnitude > scale) {
                scale = magnitude;
            }
        }

        double mean = calculateMean(snapshot, snapshotCount, scale);
        long lowerIndex = (snapshotCount - 1) / 2;
        long upperIndex = snapshotCount / 2;
        double lower = 0.0;
        double upper = 0.0;
        long index = 0;

        CompensatedSum deviations = new CompensatedSum();
        CompensatedSum squaredDeviations = new CompensatedSum();

        for (Value value = snapshot; value != null; value = value.next) {
            if (index == lowerIndex) {
                lower = value.number;
            }
            if (index == upperIndex) {
                upper = value.number;
            }
            index++;

            if (scale != 0.0) {
                double difference = value.number - mean;
                double deviation = isFinite(difference)
                        ? difference / scale
                        : value.number / scale - mean / scale;
                deviations.add(deviation);
                squaredDeviations.add(deviation * deviation);
            }
        }

        double normalizedCorrection = deviations.value() / snapshotCount;
        double normalizedVariance =
                squaredDeviations.value() / snapshotCount
                        - normalizedCorrection * normalizedCorrection;

        // Correct for the rounded mean and any negative rounding residue.
        if (normalizedVariance < 0.0) {
            normalizedVariance = 0.0;
        }
        double variance = (normalizedVariance * scale) * scale;

        return new Statistics(
                observationTime,
                snapshotCount,
                mean,
                midpoint(lower, upper),
                variance);
    }

    // The caller must hold monitor.
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

    private static double calculateMean(
            Value values, long count, double scale) {
        if (scale == 0.0) {
            return 0.0;
        }

        // Dividing first preserves small residuals after large cancellations.
        if (scale > 1.0) {
            CompensatedSum direct = new CompensatedSum();
            for (Value value = values; value != null; value = value.next) {
                direct.add(value.number / count);
            }
            double mean = direct.value();
            if (isFinite(mean)) {
                return clamp(mean, -scale, scale);
            }
        }

        // Scaling avoids overflow and protects uniformly tiny measurements.
        CompensatedSum normalized = new CompensatedSum();
        for (Value value = values; value != null; value = value.next) {
            normalized.add(value.number / scale);
        }
        double mean = clamp(normalized.value() / count, -1.0, 1.0);
        return mean * scale;
    }

    private static Value sort(Value head, long length) {
        if (length <= 1) {
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
        if (left.number <= right.number) {
            head = left;
            left = left.next;
        } else {
            head = right;
            right = right.next;
        }

        Value tail = head;
        while (left != null && right != null) {
            if (left.number <= right.number) {
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

    private static double midpoint(double left, double right) {
        double sum = left + right;
        return isFinite(sum)
                ? sum / 2.0
                : left / 2.0 + right / 2.0;
    }

    private static double absolute(double value) {
        return value < 0.0 ? -value : value;
    }

    private static boolean isFinite(double value) {
        return value >= -MAX_FINITE && value <= MAX_FINITE;
    }

    private static double clamp(double value, double minimum, double maximum) {
        if (value < minimum) {
            return minimum;
        }
        if (value > maximum) {
            return maximum;
        }
        return value;
    }
}

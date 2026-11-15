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

    private static final class ValueNode {
        private final double value;
        private ValueNode next;

        private ValueNode(double value) {
            this.value = value;
        }
    }

    private final Clock clock;
    private final Object monitor = new Object();
    private Batch head;
    private Batch tail;

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

        double[] copied = new double[measurements.length];
        for (int i = 0; i < measurements.length; i++) {
            double value = measurements[i];
            if (value != value || value == Double.POSITIVE_INFINITY
                    || value == Double.NEGATIVE_INFINITY) {
                throw new IllegalArgumentException("measurements must be finite");
            }
            copied[i] = value;
        }

        synchronized (monitor) {
            long now = clock.now();
            if (now > Long.MAX_VALUE - ttl) {
                throw new IllegalArgumentException("expiration timestamp overflows");
            }

            Batch batch = new Batch(copied, now + ttl);
            if (tail == null) {
                head = batch;
            } else {
                tail.next = batch;
            }
            tail = batch;
        }
    }

    public long removeExpired() {
        synchronized (monitor) {
            return purgeExpired(clock.now());
        }
    }

    public Statistics statistics() {
        long observationTime;
        Batch snapshotHead = null;
        Batch snapshotTail = null;

        synchronized (monitor) {
            observationTime = clock.now();
            purgeExpired(observationTime);

            for (Batch batch = head; batch != null; batch = batch.next) {
                Batch copy = new Batch(copyValues(batch.values), batch.expiration);
                if (snapshotTail == null) {
                    snapshotHead = copy;
                } else {
                    snapshotTail.next = copy;
                }
                snapshotTail = copy;
            }
        }

        ValueNode valuesHead = null;
        ValueNode valuesTail = null;
        long count = 0;
        double maxAbsoluteValue = 0.0;

        for (Batch batch = snapshotHead; batch != null; batch = batch.next) {
            for (double value : batch.values) {
                ValueNode node = new ValueNode(value);
                if (valuesTail == null) {
                    valuesHead = node;
                } else {
                    valuesTail.next = node;
                }
                valuesTail = node;
                count++;
                double absoluteValue = value < 0.0 ? -value : value;
                if (absoluteValue > maxAbsoluteValue) {
                    maxAbsoluteValue = absoluteValue;
                }
            }
        }

        if (count == 0) {
            return new Statistics(observationTime, 0, Double.NaN, Double.NaN, Double.NaN);
        }

        double normalizedMean = 0.0;
        long seen = 0;
        for (ValueNode node = valuesHead; node != null; node = node.next) {
            double normalizedValue = maxAbsoluteValue == 0.0
                    ? 0.0
                    : node.value / maxAbsoluteValue;
            seen++;
            normalizedMean += (normalizedValue - normalizedMean) / seen;
        }
        double mean = normalizedMean * maxAbsoluteValue;

        valuesHead = sort(valuesHead);
        double median;
        if ((count & 1L) != 0L) {
            long middle = count / 2;
            ValueNode node = valuesHead;
            for (long i = 0; i < middle; i++) {
                node = node.next;
            }
            median = node.value;
        } else {
            long middle = count / 2;
            ValueNode node = valuesHead;
            for (long i = 0; i < middle - 1; i++) {
                node = node.next;
            }
            double lower = node.value;
            double upper = node.next.value;
            if (lower < 0.0 && upper > 0.0) {
                median = (lower + upper) / 2.0;
            } else {
                median = lower + (upper - lower) / 2.0;
            }
        }

        double squaredDeviationSum = 0.0;
        double compensation = 0.0;
        for (ValueNode node = valuesHead; node != null; node = node.next) {
            double normalizedValue = maxAbsoluteValue == 0.0
                    ? 0.0
                    : node.value / maxAbsoluteValue;
            double deviation = normalizedValue - normalizedMean;
            double term = deviation * deviation;
            double adjusted = term - compensation;
            double updated = squaredDeviationSum + adjusted;
            compensation = (updated - squaredDeviationSum) - adjusted;
            squaredDeviationSum = updated;
        }

        double normalizedVariance = squaredDeviationSum / count;
        double populationVariance;
        if (normalizedVariance <= 1.0) {
            populationVariance = maxAbsoluteValue
                    * (maxAbsoluteValue * normalizedVariance);
        } else {
            populationVariance = (maxAbsoluteValue * normalizedVariance)
                    * maxAbsoluteValue;
        }

        return new Statistics(
                observationTime, count, mean, median, populationVariance);
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
                if (tail == current) {
                    tail = previous;
                }
            } else {
                previous = current;
            }
            current = current.next;
        }

        return removed;
    }

    private static double[] copyValues(double[] values) {
        double[] copy = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            copy[i] = values[i];
        }
        return copy;
    }

    private static ValueNode sort(ValueNode head) {
        if (head == null || head.next == null) {
            return head;
        }

        ValueNode slow = head;
        ValueNode fast = head.next;
        while (fast != null && fast.next != null) {
            slow = slow.next;
            fast = fast.next.next;
        }

        ValueNode right = slow.next;
        slow.next = null;
        return merge(sort(head), sort(right));
    }

    private static ValueNode merge(ValueNode left, ValueNode right) {
        ValueNode dummy = new ValueNode(0.0);
        ValueNode tail = dummy;

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
        return dummy.next;
    }
}

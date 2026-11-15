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

    // Batches are linked so adding or removing one does not expose internal storage.
    private Batch head;
    private long measurementCount;

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

        double[] copy = new double[measurements.length];
        for (int i = 0; i < measurements.length; i++) {
            double value = measurements[i];
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                throw new IllegalArgumentException("measurements must be finite");
            }
            copy[i] = value;
        }

        synchronized (monitor) {
            long now = clock.now();
            if (ttl > Long.MAX_VALUE - now) {
                throw new IllegalArgumentException("expiration timestamp overflows");
            }
            if (measurementCount > Long.MAX_VALUE - copy.length) {
                throw new IllegalStateException("measurement count overflows");
            }

            Batch batch = new Batch(copy, now + ttl);
            batch.next = head;
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

            if (measurementCount > Integer.MAX_VALUE) {
                throw new IllegalStateException("too many measurements for a Java array");
            }

            snapshot = new double[(int) measurementCount];
            int offset = 0;
            for (Batch batch = head; batch != null; batch = batch.next) {
                System.arraycopy(batch.values, 0, snapshot, offset, batch.values.length);
                offset += batch.values.length;
            }
        }

        return calculateStatistics(observationTime, snapshot);
    }

    // The caller must hold monitor.
    private long purgeExpired(long observationTime) {
        long removed = 0;
        Batch previous = null;
        Batch current = head;

        while (current != null) {
            if (current.expiration <= observationTime) {
                removed += current.values.length;
                measurementCount -= current.values.length;

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

    private static Statistics calculateStatistics(long observationTime, double[] values) {
        int length = values.length;
        if (length == 0) {
            return new Statistics(observationTime, 0, Double.NaN, Double.NaN, Double.NaN);
        }

        sort(values);

        double median;
        if ((length & 1) != 0) {
            median = values[length / 2];
        } else {
            median = safeAverage(values[length / 2 - 1], values[length / 2]);
        }

        double scale = 0.0;
        for (double value : values) {
            double absolute = Math.abs(value);
            if (absolute > scale) {
                scale = absolute;
            }
        }

        double mean;
        if (scale == 0.0) {
            mean = 0.0;
        } else {
            // Scaling keeps the compensated sum bounded even for very large values.
            double sum = 0.0;
            double compensation = 0.0;
            for (double value : values) {
                double term = value / scale;
                double adjusted = term - compensation;
                double next = sum + adjusted;
                compensation = (next - sum) - adjusted;
                sum = next;
            }
            double normalizedMean = sum / length;
            if (normalizedMean > 1.0) {
                normalizedMean = 1.0;
            } else if (normalizedMean < -1.0) {
                normalizedMean = -1.0;
            }
            mean = normalizedMean * scale;
        }

        double deviationScale = 0.0;
        for (double value : values) {
            double deviation = Math.abs(value - mean);
            if (Double.isInfinite(deviation)) {
                deviationScale = Double.POSITIVE_INFINITY;
                break;
            }
            if (deviation > deviationScale) {
                deviationScale = deviation;
            }
        }

        double variance;
        if (deviationScale == 0.0) {
            variance = 0.0;
        } else if (Double.isInfinite(deviationScale)) {
            variance = Double.POSITIVE_INFINITY;
        } else {
            double sumSquares = 0.0;
            double compensation = 0.0;
            for (double value : values) {
                double normalized = (value - mean) / deviationScale;
                double term = normalized * normalized;
                double adjusted = term - compensation;
                double next = sumSquares + adjusted;
                compensation = (next - sumSquares) - adjusted;
                sumSquares = next;
            }

            double factor = sumSquares / length;
            if (factor > 1.0) {
                factor = 1.0;
            }
            variance = deviationScale * (deviationScale * factor);
        }

        return new Statistics(observationTime, length, mean, median, variance);
    }

    private static double safeAverage(double first, double second) {
        if ((first < 0.0) == (second < 0.0)) {
            return first + (second - first) * 0.5;
        }
        return first * 0.5 + second * 0.5;
    }

    private static void sort(double[] values) {
        int length = values.length;
        if (length < 2) {
            return;
        }

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

    private static void siftDown(double[] values, int root, int end) {
        while (root <= (end - 2) / 2) {
            int child = root * 2 + 1;
            if (child + 1 < end && Double.compare(values[child], values[child + 1]) < 0) {
                child++;
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

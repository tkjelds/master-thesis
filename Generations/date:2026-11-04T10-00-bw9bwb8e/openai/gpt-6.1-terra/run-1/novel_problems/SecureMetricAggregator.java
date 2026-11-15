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

    private final Clock clock;
    private final Object monitor = new Object();
    private Batch batches;
    private long count;

    public SecureMetricAggregator(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.clock = clock;
    }

    /*
     * Storage is unbounded apart from available memory and the long count
     * limit. Operations do not wait for capacity or for data to arrive.
     */
    public void put(double[] measurements, long ttl) {
        if (measurements == null || measurements.length == 0 || ttl <= 0) {
            throw new IllegalArgumentException("invalid measurements or TTL");
        }

        double[] copy = new double[measurements.length];
        for (int i = 0; i < measurements.length; i++) {
            double value = measurements[i];
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("measurements must be finite");
            }
            copy[i] = value;
        }

        synchronized (monitor) {
            long now = clock.now();
            if (ttl > Long.MAX_VALUE - now) {
                throw new IllegalArgumentException("expiration timestamp overflow");
            }
            if (count > Long.MAX_VALUE - copy.length) {
                throw new IllegalStateException("measurement count overflow");
            }

            Batch batch = new Batch(copy, now + ttl, batches);
            batches = batch;
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
        SnapshotBatch snapshot = null;

        synchronized (monitor) {
            observationTime = clock.now();
            purgeExpired(observationTime);
            snapshotCount = count;

            for (Batch batch = batches; batch != null; batch = batch.next) {
                double[] copy = new double[batch.values.length];
                for (int i = 0; i < copy.length; i++) {
                    copy[i] = batch.values[i];
                }
                snapshot = new SnapshotBatch(copy, snapshot);
            }
        }

        if (snapshotCount == 0) {
            return new Statistics(
                    observationTime, 0, Double.NaN, Double.NaN, Double.NaN);
        }

        double scale = 0.0;
        for (SnapshotBatch batch = snapshot; batch != null; batch = batch.next) {
            for (int i = 0; i < batch.values.length; i++) {
                double absolute = Math.abs(batch.values[i]);
                if (absolute > scale) {
                    scale = absolute;
                }
            }
        }

        double mean;
        double variance;
        if (scale == 0.0) {
            mean = 0.0;
            variance = 0.0;
        } else {
            // Compensated summation of scaled values avoids sum overflow.
            double sum = 0.0;
            double correction = 0.0;
            for (SnapshotBatch batch = snapshot; batch != null; batch = batch.next) {
                for (int i = 0; i < batch.values.length; i++) {
                    double value = batch.values[i] / scale;
                    double next = sum + value;
                    if (Math.abs(sum) >= Math.abs(value)) {
                        correction += (sum - next) + value;
                    } else {
                        correction += (value - next) + sum;
                    }
                    sum = next;
                }
            }
            mean = ((sum + correction) / (double) snapshotCount) * scale;

            double squaredSum = 0.0;
            double squaredCorrection = 0.0;
            for (SnapshotBatch batch = snapshot; batch != null; batch = batch.next) {
                for (int i = 0; i < batch.values.length; i++) {
                    double value = batch.values[i];
                    double difference = value - mean;
                    double normalized;
                    if (Double.isFinite(difference)) {
                        normalized = difference / scale;
                    } else {
                        normalized = value / scale - mean / scale;
                    }
                    double square = normalized * normalized;
                    double next = squaredSum + square;
                    if (Math.abs(squaredSum) >= Math.abs(square)) {
                        squaredCorrection += (squaredSum - next) + square;
                    } else {
                        squaredCorrection += (square - next) + squaredSum;
                    }
                    squaredSum = next;
                }
            }
            double normalizedVariance =
                    (squaredSum + squaredCorrection) / (double) snapshotCount;
            variance = (normalizedVariance * scale) * scale;
        }

        double upper = select(snapshot, snapshotCount / 2);
        double median;
        if ((snapshotCount & 1L) != 0) {
            median = upper;
        } else {
            double lower = select(snapshot, snapshotCount / 2 - 1);
            if (lower == upper) {
                median = lower;
            } else if (lower < 0.0 && upper > 0.0) {
                median = (lower + upper) / 2.0;
            } else {
                median = lower + (upper - lower) / 2.0;
            }
        }

        return new Statistics(observationTime, snapshotCount, mean, median, variance);
    }

    // Called only while holding monitor.
    private long purgeExpired(long now) {
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
        count -= removed;
        return removed;
    }

    /*
     * Select by binary search over the ordered bit representations of finite
     * doubles. This uses constant auxiliary space and never modifies a snapshot.
     */
    private static double select(SnapshotBatch snapshot, long rank) {
        long low = orderedKey(-Double.MAX_VALUE);
        long high = orderedKey(Double.MAX_VALUE);

        while (low < high) {
            long middle = (low & high) + ((low ^ high) >> 1);
            long atOrBelow = 0;

            for (SnapshotBatch batch = snapshot; batch != null; batch = batch.next) {
                for (int i = 0; i < batch.values.length; i++) {
                    if (orderedKey(batch.values[i]) <= middle) {
                        atOrBelow++;
                    }
                }
            }

            if (atOrBelow <= rank) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }

        long bits = low < 0 ? low ^ Long.MAX_VALUE : low;
        return Double.longBitsToDouble(bits);
    }

    private static long orderedKey(double value) {
        long bits = Double.doubleToRawLongBits(value);
        return bits < 0 ? bits ^ Long.MAX_VALUE : bits;
    }
}

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
        private final double[] measurements;
        private final long expirationTime;
        private Batch next;

        private Batch(double[] measurements, long expirationTime) {
            this.measurements = measurements;
            this.expirationTime = expirationTime;
        }
    }

    private static final class SnapshotBatch {
        private final double[] measurements;
        private int index;
        private SnapshotBatch next;

        private SnapshotBatch(double[] measurements) {
            this.measurements = measurements;
        }
    }

    private final Clock clock;
    private final Object monitor = new Object();
    private Batch head;
    private Batch tail;
    private long activeCount;

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

        long now = clock.now();
        if (ttl > Long.MAX_VALUE - now) {
            throw new IllegalArgumentException("expiration timestamp overflows");
        }
        Batch batch = new Batch(copy, now + ttl);

        synchronized (monitor) {
            if (Long.MAX_VALUE - activeCount < copy.length) {
                throw new IllegalStateException("measurement count overflows");
            }
            if (tail == null) {
                head = batch;
            } else {
                tail.next = batch;
            }
            tail = batch;
            activeCount += copy.length;
        }
    }

    public long removeExpired() {
        long now = clock.now();
        synchronized (monitor) {
            return purgeExpired(now);
        }
    }

    public Statistics statistics() {
        long now = clock.now();
        SnapshotBatch snapshotHead = null;
        SnapshotBatch snapshotTail = null;
        long snapshotCount;

        synchronized (monitor) {
            purgeExpired(now);
            snapshotCount = activeCount;

            for (Batch batch = head; batch != null; batch = batch.next) {
                SnapshotBatch snapshot = new SnapshotBatch(batch.measurements.clone());
                if (snapshotTail == null) {
                    snapshotHead = snapshot;
                } else {
                    snapshotTail.next = snapshot;
                }
                snapshotTail = snapshot;
            }
        }

        if (snapshotCount == 0) {
            return new Statistics(now, 0, Double.NaN, Double.NaN, Double.NaN);
        }

        double scale = 0.0;
        for (SnapshotBatch batch = snapshotHead; batch != null; batch = batch.next) {
            for (double value : batch.measurements) {
                double absoluteValue = Math.abs(value);
                if (absoluteValue > scale) {
                    scale = absoluteValue;
                }
            }
            heapSort(batch.measurements);
        }

        double scaledMean = 0.0;
        if (scale != 0.0) {
            double sum = 0.0;
            double compensation = 0.0;
            for (SnapshotBatch batch = snapshotHead; batch != null; batch = batch.next) {
                for (double value : batch.measurements) {
                    double adjusted = value / scale - compensation;
                    double updated = sum + adjusted;
                    compensation = (updated - sum) - adjusted;
                    sum = updated;
                }
            }
            scaledMean = sum / (double) snapshotCount;
        }
        double mean = scaledMean * scale;

        double firstMiddle = 0.0;
        double secondMiddle = 0.0;
        long firstRank = (snapshotCount - 1) / 2;
        long secondRank = snapshotCount / 2;
        for (long rank = 0; rank <= secondRank; rank++) {
            SnapshotBatch minimumBatch = null;
            double minimum = 0.0;
            for (SnapshotBatch batch = snapshotHead; batch != null; batch = batch.next) {
                if (batch.index < batch.measurements.length
                        && (minimumBatch == null
                        || batch.measurements[batch.index] < minimum)) {
                    minimumBatch = batch;
                    minimum = batch.measurements[batch.index];
                }
            }
            double value = minimumBatch.measurements[minimumBatch.index++];
            if (rank == firstRank) {
                firstMiddle = value;
            }
            if (rank == secondRank) {
                secondMiddle = value;
            }
        }
        double median = firstRank == secondRank
                ? firstMiddle
                : midpoint(firstMiddle, secondMiddle);

        double variance = 0.0;
        if (scale != 0.0) {
            double sumSquares = 0.0;
            double compensation = 0.0;
            for (SnapshotBatch batch = snapshotHead; batch != null; batch = batch.next) {
                for (double value : batch.measurements) {
                    double difference = value / scale - scaledMean;
                    double term = difference * difference;
                    double adjusted = term - compensation;
                    double updated = sumSquares + adjusted;
                    compensation = (updated - sumSquares) - adjusted;
                    sumSquares = updated;
                }
            }
            double scaledStandardDeviation =
                    Math.sqrt(sumSquares / (double) snapshotCount);
            double standardDeviation = scale * scaledStandardDeviation;
            variance = standardDeviation * standardDeviation;
        }

        return new Statistics(now, snapshotCount, mean, median, variance);
    }

    private long purgeExpired(long now) {
        long removed = 0;
        Batch previous = null;
        Batch current = head;

        while (current != null) {
            if (current.expirationTime <= now) {
                removed += current.measurements.length;
                activeCount -= current.measurements.length;
                if (previous == null) {
                    head = current.next;
                } else {
                    previous.next = current.next;
                }
                if (tail == current) {
                    tail = previous;
                }
                current = current.next;
            } else {
                previous = current;
                current = current.next;
            }
        }

        return removed;
    }

    private static double midpoint(double left, double right) {
        if ((left < 0.0) != (right < 0.0)) {
            return (left + right) / 2.0;
        }
        return left + (right - left) / 2.0;
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

    private static void siftDown(double[] values, int root, int length) {
        while (root < length / 2) {
            int child = root * 2 + 1;
            if (child + 1 < length && values[child] < values[child + 1]) {
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

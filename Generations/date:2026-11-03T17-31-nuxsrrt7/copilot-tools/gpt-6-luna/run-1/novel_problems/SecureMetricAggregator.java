@SecureMetricAggregator.ThreadSafe
public final class SecureMetricAggregator {
    @interface ThreadSafe {}

    @FunctionalInterface
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

    private static final double MAX_FINITE = 1.7976931348623157E308;

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
    private Batch tail;

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
            if (!(value >= -MAX_FINITE && value <= MAX_FINITE)) {
                throw new IllegalArgumentException("measurements must be finite");
            }
            copy[i] = value;
        }

        synchronized (monitor) {
            long now = clock.now();
            if (now > Long.MAX_VALUE - ttl) {
                throw new IllegalArgumentException("expiration timestamp overflows");
            }
            Batch batch = new Batch(copy, now + ttl);
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
        final long observationTime;
        final double[] snapshot;

        synchronized (monitor) {
            observationTime = clock.now();
            purgeExpired(observationTime);

            long total = 0;
            for (Batch batch = head; batch != null; batch = batch.next) {
                total += batch.values.length;
            }
            if (total > Integer.MAX_VALUE) {
                throw new IllegalStateException("too many measurements for a snapshot");
            }

            snapshot = new double[(int) total];
            int offset = 0;
            for (Batch batch = head; batch != null; batch = batch.next) {
                for (int i = 0; i < batch.values.length; i++) {
                    snapshot[offset++] = batch.values[i];
                }
            }
        }

        if (snapshot.length == 0) {
            double nan = 0.0 / 0.0;
            return new Statistics(observationTime, 0, nan, nan, nan);
        }

        double mean = 0.0;
        double m2 = 0.0;
        long count = 0;
        for (double value : snapshot) {
            count++;
            double denominator = (double) count;
            double delta = value - mean;

            if (delta >= -MAX_FINITE && delta <= MAX_FINITE) {
                mean += delta / denominator;
            } else {
                mean = mean * (((double) (count - 1)) / denominator)
                        + value / denominator;
            }

            double deltaAfterMean = value - mean;
            m2 += delta * deltaAfterMean;
        }

        heapSort(snapshot);
        double median;
        int middle = snapshot.length / 2;
        if ((snapshot.length & 1) != 0) {
            median = snapshot[middle];
        } else {
            double lower = snapshot[middle - 1];
            double upper = snapshot[middle];
            median = lower / 2.0 + upper / 2.0;
        }

        double variance = m2 / count;
        if (variance < 0.0) {
            variance = 0.0;
        }
        return new Statistics(observationTime, count, mean, median, variance);
    }

    private long purgeExpired(long observationTime) {
        long removed = 0;
        Batch previous = null;
        Batch current = head;

        while (current != null) {
            Batch next = current.next;
            if (current.expiration <= observationTime) {
                removed += current.values.length;
                if (previous == null) {
                    head = next;
                } else {
                    previous.next = next;
                }
                if (tail == current) {
                    tail = previous;
                }
            } else {
                previous = current;
            }
            current = next;
        }
        return removed;
    }

    private static void heapSort(double[] values) {
        int length = values.length;
        for (int root = length / 2 - 1; root >= 0; root--) {
            siftDown(values, root, length);
        }
        for (int end = length - 1; end > 0; end--) {
            double value = values[0];
            values[0] = values[end];
            values[end] = value;
            siftDown(values, 0, end);
        }
    }

    private static void siftDown(double[] values, int root, int end) {
        while (true) {
            long left = (long) root * 2 + 1;
            if (left >= end) {
                return;
            }

            long child = left;
            long right = left + 1;
            if (right < end && values[(int) right] > values[(int) left]) {
                child = right;
            }
            if (values[root] >= values[(int) child]) {
                return;
            }

            double value = values[root];
            values[root] = values[(int) child];
            values[(int) child] = value;
            root = (int) child;
        }
    }
}

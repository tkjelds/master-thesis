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

    private final Clock clock;
    private final Object monitor = new Object();
    private Batch head;
    private Batch tail;

    public SecureMetricAggregator(Clock clock) {
        if (clock == null) {
            throw new NullPointerException("clock");
        }
        this.clock = clock;
    }

    public void put(double[] measurements, long ttl) {
        if (measurements == null) {
            throw new NullPointerException("measurements");
        }
        if (measurements.length == 0) {
            throw new IllegalArgumentException("measurements must not be empty");
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
            throw new ArithmeticException("expiration timestamp overflow");
        }
        Batch batch = new Batch(copy, now + ttl);

        synchronized (monitor) {
            if (tail == null) {
                head = batch;
                tail = batch;
            } else {
                tail.next = batch;
                tail = batch;
            }
        }
    }

    public long removeExpired() {
        long observationTime = clock.now();
        long removed = 0;

        synchronized (monitor) {
            Batch previous = null;
            Batch current = head;
            while (current != null) {
                Batch next = current.next;
                if (current.expirationTime <= observationTime) {
                    removed += current.measurements.length;
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
        }

        return removed;
    }

    public Statistics statistics() {
        long observationTime = clock.now();
        double[] snapshot;

        synchronized (monitor) {
            Batch previous = null;
            Batch current = head;
            long total = 0;

            while (current != null) {
                Batch next = current.next;
                if (current.expirationTime <= observationTime) {
                    if (previous == null) {
                        head = next;
                    } else {
                        previous.next = next;
                    }
                    if (tail == current) {
                        tail = previous;
                    }
                } else {
                    total += current.measurements.length;
                    previous = current;
                }
                current = next;
            }

            if (total > Integer.MAX_VALUE) {
                throw new IllegalStateException("too many measurements to create a snapshot");
            }

            snapshot = new double[(int) total];
            int offset = 0;
            for (current = head; current != null; current = current.next) {
                int length = current.measurements.length;
                System.arraycopy(current.measurements, 0, snapshot, offset, length);
                offset += length;
            }
        }

        if (snapshot.length == 0) {
            return new Statistics(observationTime, 0, Double.NaN, Double.NaN, Double.NaN);
        }

        double mean = 0.0;
        double sumSquaredDeviations = 0.0;
        long count = 0;
        for (double value : snapshot) {
            long nextCount = count + 1;
            double previousMean = mean;
            double weight = 1.0 / nextCount;
            mean = previousMean * (1.0 - weight) + value * weight;
            double delta = value - previousMean;
            double deltaAfterUpdate = value - mean;
            sumSquaredDeviations += delta * deltaAfterUpdate;
            count = nextCount;
        }

        sort(snapshot);
        double median;
        int middle = snapshot.length / 2;
        if ((snapshot.length & 1) == 1) {
            median = snapshot[middle];
        } else {
            median = snapshot[middle - 1] / 2.0 + snapshot[middle] / 2.0;
        }

        double variance = sumSquaredDeviations / count;
        if (variance < 0.0) {
            variance = 0.0;
        }
        return new Statistics(observationTime, count, mean, median, variance);
    }

    private static void sort(double[] values) {
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
        while (root * 2 + 1 < end) {
            int child = root * 2 + 1;
            if (child + 1 < end && values[child] < values[child + 1]) {
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

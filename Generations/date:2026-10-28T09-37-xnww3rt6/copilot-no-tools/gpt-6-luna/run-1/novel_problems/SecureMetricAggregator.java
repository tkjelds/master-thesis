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

        double[] values = measurements.clone();
        for (double value : values) {
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                throw new IllegalArgumentException("measurements must be finite");
            }
        }

        long now = clock.now();
        if (now > Long.MAX_VALUE - ttl) {
            throw new IllegalArgumentException("expiration timestamp overflows");
        }

        Batch batch = new Batch(values, now + ttl);
        synchronized (monitor) {
            if (tail == null) {
                head = batch;
            } else {
                tail.next = batch;
            }
            tail = batch;
        }
    }

    public long removeExpired() {
        long now = clock.now();
        long removed = 0;

        synchronized (monitor) {
            Batch previous = null;
            Batch current = head;
            while (current != null) {
                Batch next = current.next;
                if (current.expiration <= now) {
                    removed += current.values.length;
                    if (previous == null) {
                        head = next;
                    } else {
                        previous.next = next;
                    }
                    if (tail == current) {
                        tail = previous;
                    }
                    current.next = null;
                } else {
                    previous = current;
                }
                current = next;
            }
        }

        return removed;
    }

    public Statistics statistics() {
        long now = clock.now();
        double[] snapshot;

        synchronized (monitor) {
            purgeExpired(now);

            long total = 0;
            for (Batch batch = head; batch != null; batch = batch.next) {
                if (total > Integer.MAX_VALUE - (long) batch.values.length) {
                    throw new IllegalStateException("too many measurements to snapshot");
                }
                total += batch.values.length;
            }

            snapshot = new double[(int) total];
            int offset = 0;
            for (Batch batch = head; batch != null; batch = batch.next) {
                System.arraycopy(batch.values, 0, snapshot, offset, batch.values.length);
                offset += batch.values.length;
            }
        }

        if (snapshot.length == 0) {
            return new Statistics(now, 0, Double.NaN, Double.NaN, Double.NaN);
        }

        return calculateStatistics(now, snapshot);
    }

    private void purgeExpired(long now) {
        Batch previous = null;
        Batch current = head;

        while (current != null) {
            Batch next = current.next;
            if (current.expiration <= now) {
                if (previous == null) {
                    head = next;
                } else {
                    previous.next = next;
                }
                if (tail == current) {
                    tail = previous;
                }
                current.next = null;
            } else {
                previous = current;
            }
            current = next;
        }
    }

    private static Statistics calculateStatistics(long observationTime, double[] values) {
        double scale = 0.0;
        for (double value : values) {
            double magnitude = value < 0.0 ? -value : value;
            if (magnitude > scale) {
                scale = magnitude;
            }
        }

        double mean = 0.0;
        double sumSquaredDeviations = 0.0;
        if (scale != 0.0) {
            for (int i = 0; i < values.length; i++) {
                double normalized = values[i] / scale;
                double delta = normalized - mean;
                mean += delta / (i + 1.0);
                sumSquaredDeviations += delta * (normalized - mean);
            }
            if (sumSquaredDeviations < 0.0) {
                sumSquaredDeviations = 0.0;
            }
            mean *= scale;
        }

        sort(values);
        int middle = values.length / 2;
        double median;
        if ((values.length & 1) != 0) {
            median = values[middle];
        } else {
            double lower = values[middle - 1];
            double upper = values[middle];
            if (lower < 0.0 && upper > 0.0) {
                median = (lower + upper) / 2.0;
            } else {
                median = lower + (upper - lower) / 2.0;
            }
        }

        double variance = 0.0;
        if (scale != 0.0) {
            variance = (sumSquaredDeviations / values.length) * scale * scale;
        }

        return new Statistics(observationTime, values.length, mean, median, variance);
    }

    private static void sort(double[] values) {
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

    private static void siftDown(double[] values, int root, int size) {
        while (root < size / 2) {
            int child = root * 2 + 1;
            if (child + 1 < size && values[child] < values[child + 1]) {
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

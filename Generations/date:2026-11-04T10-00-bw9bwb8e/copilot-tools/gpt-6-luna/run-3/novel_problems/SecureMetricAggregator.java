@SecureMetricAggregator.ThreadSafe
public final class SecureMetricAggregator {
    @interface ThreadSafe {}

    public interface Clock {
        long now();
    }

    public record Statistics(long observationTime, long count, double mean,
                             double median, double populationVariance) {
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
            throw new NullPointerException("clock");
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
            if (value != value || value > Double.MAX_VALUE || value < -Double.MAX_VALUE) {
                throw new IllegalArgumentException("measurements must be finite");
            }
            copy[i] = value;
        }

        synchronized (monitor) {
            long now = clock.now();
            if (ttl > Long.MAX_VALUE - now) {
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
            long now = clock.now();
            return purgeExpired(now);
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
                if (total > Integer.MAX_VALUE) {
                    throw new IllegalStateException("too many measurements to snapshot");
                }
            }

            snapshot = new double[(int) total];
            int offset = 0;
            for (Batch batch = head; batch != null; batch = batch.next) {
                System.arraycopy(batch.values, 0, snapshot, offset, batch.values.length);
                offset += batch.values.length;
            }
        }

        if (snapshot.length == 0) {
            double nan = 0.0 / 0.0;
            return new Statistics(observationTime, 0, nan, nan, nan);
        }

        double mean = calculateMean(snapshot);
        heapSort(snapshot);
        double median;
        int middle = snapshot.length / 2;
        if ((snapshot.length & 1) != 0) {
            median = snapshot[middle];
        } else {
            median = midpoint(snapshot[middle - 1], snapshot[middle]);
        }
        double variance = calculatePopulationVariance(snapshot, mean);
        return new Statistics(observationTime, snapshot.length, mean, median, variance);
    }

    private long purgeExpired(long now) {
        long removed = 0;
        Batch previous = null;
        Batch current = head;
        while (current != null) {
            if (current.expiration <= now) {
                removed += current.values.length;
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

    private static double calculateMean(double[] values) {
        double mean = 0.0;
        for (int i = 0; i < values.length; i++) {
            double count = i + 1.0;
            mean = mean * ((count - 1.0) / count) + values[i] / count;
        }
        return mean;
    }

    private static double midpoint(double left, double right) {
        if (left < 0.0 && right > 0.0) {
            return (left + right) / 2.0;
        }
        return left / 2.0 + right / 2.0;
    }

    private static double calculatePopulationVariance(double[] values, double mean) {
        double scale = 0.0;
        double scaledSquares = 0.0;
        for (int i = 0; i < values.length; i++) {
            double difference = values[i] - mean;
            double absoluteDifference = difference < 0.0 ? -difference : difference;
            if (absoluteDifference == Double.POSITIVE_INFINITY) {
                return Double.POSITIVE_INFINITY;
            }
            if (absoluteDifference != 0.0) {
                if (scale < absoluteDifference) {
                    double ratio = scale / absoluteDifference;
                    scaledSquares = 1.0 + scaledSquares * ratio * ratio;
                    scale = absoluteDifference;
                } else {
                    double ratio = absoluteDifference / scale;
                    scaledSquares += ratio * ratio;
                }
            }
        }
        if (scale == 0.0) {
            return 0.0;
        }
        double standardDeviation = scale * Math.sqrt(scaledSquares / values.length);
        return standardDeviation * standardDeviation;
    }

    private static void heapSort(double[] values) {
        int length = values.length;
        for (int start = length / 2 - 1; start >= 0; start--) {
            siftDown(values, start, length - 1);
        }
        for (int end = length - 1; end > 0; end--) {
            double value = values[0];
            values[0] = values[end];
            values[end] = value;
            siftDown(values, 0, end - 1);
        }
    }

    private static void siftDown(double[] values, int root, int end) {
        while (true) {
            int child = root * 2 + 1;
            if (child > end) {
                return;
            }
            if (child + 1 <= end && values[child] < values[child + 1]) {
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

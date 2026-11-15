@SecureMetricAggregator.ThreadSafe
public final class SecureMetricAggregator {
    @interface ThreadSafe {}

    public interface Clock {
        long now();
    }

    public record Statistics(long observationTime, long count, double mean, double median, double populationVariance) {
    }

    private static final class Batch {
        final double[] measurements;
        final long expirationTime;
        Batch next;

        Batch(double[] measurements, long expirationTime) {
            this.measurements = measurements;
            this.expirationTime = expirationTime;
        }
    }

    private static final class SnapshotBatch {
        final double[] measurements;
        SnapshotBatch next;

        SnapshotBatch(double[] measurements) {
            this.measurements = measurements;
        }
    }

    private static final class Cursor {
        final double[] measurements;
        int index;

        Cursor(double[] measurements) {
            this.measurements = measurements;
        }

        double value() {
            return measurements[index];
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
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                throw new IllegalArgumentException("measurements must be finite");
            }
            copy[i] = value;
        }

        long now = clock.now();
        if (now > Long.MAX_VALUE - ttl) {
            throw new IllegalArgumentException("expiration timestamp overflows");
        }

        Batch batch = new Batch(copy, now + ttl);
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
        synchronized (monitor) {
            return purgeExpired(now);
        }
    }

    public Statistics statistics() {
        long now = clock.now();
        SnapshotBatch snapshotHead = null;
        SnapshotBatch snapshotTail = null;
        long count = 0;
        int batchCount = 0;

        synchronized (monitor) {
            purgeExpired(now);
            for (Batch batch = head; batch != null; batch = batch.next) {
                double[] copy = new double[batch.measurements.length];
                System.arraycopy(batch.measurements, 0, copy, 0, copy.length);

                SnapshotBatch snapshot = new SnapshotBatch(copy);
                if (snapshotTail == null) {
                    snapshotHead = snapshot;
                } else {
                    snapshotTail.next = snapshot;
                }
                snapshotTail = snapshot;

                count += copy.length;
                if (batchCount == Integer.MAX_VALUE) {
                    throw new IllegalStateException("too many batches to calculate statistics");
                }
                batchCount++;
            }
        }

        if (count == 0) {
            return new Statistics(now, 0, Double.NaN, Double.NaN, Double.NaN);
        }

        double[][] sortedBatches = new double[batchCount][];
        Cursor[] heap = new Cursor[batchCount];
        int i = 0;
        for (SnapshotBatch batch = snapshotHead; batch != null; batch = batch.next) {
            sort(batch.measurements);
            sortedBatches[i] = batch.measurements;
            heap[i] = new Cursor(batch.measurements);
            i++;
        }

        int heapSize = heap.length;
        for (i = heapSize / 2 - 1; i >= 0; i--) {
            siftDown(heap, i, heapSize);
        }

        double mean = 0.0;
        double m2 = 0.0;
        double lowerMiddle = 0.0;
        double upperMiddle = 0.0;
        long seen = 0;

        while (seen < count) {
            double value = heap[0].value();
            seen++;

            double newMean = mean * ((seen - 1.0) / seen) + value / seen;
            double delta = value - mean;
            m2 += delta * (value - newMean);
            mean = newMean;

            long lowerIndex = (count - 1) / 2;
            long upperIndex = count / 2;
            if (seen - 1 == lowerIndex) {
                lowerMiddle = value;
            }
            if (seen - 1 == upperIndex) {
                upperMiddle = value;
            }

            Cursor root = heap[0];
            if (root.index + 1 < root.measurements.length) {
                root.index++;
            } else {
                heapSize--;
                if (heapSize == 0) {
                    break;
                }
                heap[0] = heap[heapSize];
            }
            siftDown(heap, 0, heapSize);
        }

        if (m2 < 0.0) {
            m2 = 0.0;
        }
        double median = midpoint(lowerMiddle, upperMiddle);
        return new Statistics(now, count, mean, median, m2 / count);
    }

    private long purgeExpired(long now) {
        long removed = 0;
        Batch previous = null;
        Batch current = head;

        while (current != null) {
            if (current.expirationTime <= now) {
                removed += current.measurements.length;
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

    private static double midpoint(double a, double b) {
        if ((a < 0.0) == (b < 0.0)) {
            return a + (b - a) / 2.0;
        }
        return a / 2.0 + b / 2.0;
    }

    private static void sort(double[] values) {
        for (int i = values.length / 2 - 1; i >= 0; i--) {
            siftDown(values, i, values.length);
        }
        for (int end = values.length - 1; end > 0; end--) {
            double value = values[0];
            values[0] = values[end];
            values[end] = value;
            siftDown(values, 0, end);
        }
    }

    private static void siftDown(double[] values, int root, int size) {
        while (root * 2 + 1 < size) {
            int child = root * 2 + 1;
            if (child + 1 < size && Double.compare(values[child], values[child + 1]) < 0) {
                child++;
            }
            if (Double.compare(values[root], values[child]) >= 0) {
                return;
            }
            double value = values[root];
            values[root] = values[child];
            values[child] = value;
            root = child;
        }
    }

    private static void siftDown(Cursor[] heap, int root, int size) {
        while (root * 2 + 1 < size) {
            int child = root * 2 + 1;
            if (child + 1 < size && Double.compare(heap[child].value(), heap[child + 1].value()) > 0) {
                child++;
            }
            if (Double.compare(heap[root].value(), heap[child].value()) <= 0) {
                return;
            }
            Cursor cursor = heap[root];
            heap[root] = heap[child];
            heap[child] = cursor;
            root = child;
        }
    }
}

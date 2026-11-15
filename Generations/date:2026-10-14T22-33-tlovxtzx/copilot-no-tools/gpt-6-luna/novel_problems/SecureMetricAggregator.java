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

    private static final double MAX_DOUBLE = 1.7976931348623157E308;
    private static final long MAX_LONG = 9223372036854775807L;

    private static final class Batch {
        private final double[] values;
        private final long expiration;

        private Batch(double[] values, long expiration) {
            this.values = values;
            this.expiration = expiration;
        }
    }

    private final Clock clock;
    private final Object monitor = new Object();
    private Batch[] batches = new Batch[8];
    private int batchCount;

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

        double[] copy = new double[measurements.length];
        for (int i = 0; i < measurements.length; i++) {
            copy[i] = measurements[i];
            if (copy[i] != copy[i] || copy[i] > MAX_DOUBLE || copy[i] < -MAX_DOUBLE) {
                throw new IllegalArgumentException("measurements must be finite");
            }
        }

        long now = clock.now();
        if (now > MAX_LONG - ttl) {
            throw new IllegalArgumentException("expiration timestamp overflows");
        }
        long expiration = now + ttl;

        synchronized (monitor) {
            ensureCapacity();
            batches[batchCount++] = new Batch(copy, expiration);
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
        double[][] snapshot;
        long count;

        synchronized (monitor) {
            purgeExpired(now);
            snapshot = new double[batchCount][];
            count = 0;
            for (int i = 0; i < batchCount; i++) {
                double[] source = batches[i].values;
                double[] copy = new double[source.length];
                for (int j = 0; j < source.length; j++) {
                    copy[j] = source[j];
                }
                snapshot[i] = copy;
                count += source.length;
            }
        }

        if (count == 0) {
            double nan = notANumber();
            return new Statistics(now, 0, nan, nan, nan);
        }

        double scale = 0.0;
        for (int i = 0; i < snapshot.length; i++) {
            for (int j = 0; j < snapshot[i].length; j++) {
                double value = snapshot[i][j];
                double magnitude = value < 0.0 ? -value : value;
                if (magnitude > scale) {
                    scale = magnitude;
                }
            }
        }

        double normalizedMean = 0.0;
        if (scale != 0.0) {
            double sum = 0.0;
            double compensation = 0.0;
            for (int i = 0; i < snapshot.length; i++) {
                for (int j = 0; j < snapshot[i].length; j++) {
                    double normalized = snapshot[i][j] / scale;
                    double adjusted = normalized - compensation;
                    double next = sum + adjusted;
                    compensation = (next - sum) - adjusted;
                    sum = next;
                }
            }
            normalizedMean = sum / (double) count;
        }
        double mean = normalizedMean * scale;

        double normalizedVariance = 0.0;
        if (scale != 0.0) {
            double sumSquares = 0.0;
            double compensation = 0.0;
            for (int i = 0; i < snapshot.length; i++) {
                for (int j = 0; j < snapshot[i].length; j++) {
                    double difference = snapshot[i][j] / scale - normalizedMean;
                    double square = difference * difference;
                    double adjusted = square - compensation;
                    double next = sumSquares + adjusted;
                    compensation = (next - sumSquares) - adjusted;
                    sumSquares = next;
                }
            }
            normalizedVariance = sumSquares / (double) count;
        }
        double variance = (scale * normalizedVariance) * scale;

        double median = calculateMedian(snapshot, count);
        return new Statistics(now, count, mean, median, variance);
    }

    private void ensureCapacity() {
        if (batchCount < batches.length) {
            return;
        }
        if (batches.length == 2147483647) {
            throw new OutOfMemoryError("too many batches");
        }

        int newCapacity = batches.length <= 1073741823
                ? batches.length * 2
                : 2147483647;
        Batch[] expanded = new Batch[newCapacity];
        for (int i = 0; i < batchCount; i++) {
            expanded[i] = batches[i];
        }
        batches = expanded;
    }

    private long purgeExpired(long now) {
        int retained = 0;
        long removed = 0;

        for (int i = 0; i < batchCount; i++) {
            Batch batch = batches[i];
            if (batch.expiration <= now) {
                removed += batch.values.length;
            } else {
                batches[retained++] = batch;
            }
        }

        for (int i = retained; i < batchCount; i++) {
            batches[i] = null;
        }
        batchCount = retained;
        return removed;
    }

    private static double calculateMedian(double[][] values, long count) {
        for (int i = 0; i < values.length; i++) {
            heapSort(values[i]);
        }

        int[] positions = new int[values.length];
        int[] heap = new int[values.length];
        int heapSize = 0;

        for (int i = 0; i < values.length; i++) {
            int child = heapSize++;
            heap[child] = i;
            while (child > 0) {
                int parent = (child - 1) / 2;
                if (!batchLess(heap[child], heap[parent], values, positions)) {
                    break;
                }
                int temporary = heap[child];
                heap[child] = heap[parent];
                heap[parent] = temporary;
                child = parent;
            }
        }

        long lowerIndex = (count - 1) / 2;
        long upperIndex = count / 2;
        double lowerValue = 0.0;
        double upperValue = 0.0;

        for (long index = 0; index <= upperIndex; index++) {
            int batch = heap[0];
            double value = values[batch][positions[batch]];
            if (index == lowerIndex) {
                lowerValue = value;
            }
            if (index == upperIndex) {
                upperValue = value;
            }

            positions[batch]++;
            if (positions[batch] == values[batch].length) {
                heapSize--;
                if (heapSize > 0) {
                    heap[0] = heap[heapSize];
                    siftBatchHeapDown(heap, heapSize, values, positions);
                }
            } else {
                siftBatchHeapDown(heap, heapSize, values, positions);
            }
        }

        if (lowerValue < 0.0 && upperValue >= 0.0
                || lowerValue >= 0.0 && upperValue < 0.0) {
            return (lowerValue + upperValue) / 2.0;
        }
        return lowerValue + (upperValue - lowerValue) / 2.0;
    }

    private static boolean batchLess(
            int first, int second, double[][] values, int[] positions) {
        return values[first][positions[first]] < values[second][positions[second]];
    }

    private static void siftBatchHeapDown(
            int[] heap, int size, double[][] values, int[] positions) {
        int root = 0;
        while (true) {
            if (size <= 1 || root > (size - 2) / 2) {
                return;
            }
            int child = root * 2 + 1;
            if (child + 1 < size
                    && batchLess(heap[child + 1], heap[child], values, positions)) {
                child++;
            }
            if (!batchLess(heap[child], heap[root], values, positions)) {
                return;
            }
            int temporary = heap[root];
            heap[root] = heap[child];
            heap[child] = temporary;
            root = child;
        }
    }

    private static void heapSort(double[] values) {
        for (int start = (values.length - 2) / 2; start >= 0; start--) {
            siftValueHeapDown(values, start, values.length - 1);
        }
        for (int end = values.length - 1; end > 0; end--) {
            double temporary = values[0];
            values[0] = values[end];
            values[end] = temporary;
            siftValueHeapDown(values, 0, end - 1);
        }
    }

    private static void siftValueHeapDown(double[] values, int root, int end) {
        while (true) {
            if (end <= 0 || root > (end - 1) / 2) {
                return;
            }
            int child = root * 2 + 1;
            if (child + 1 <= end && values[child] < values[child + 1]) {
                child++;
            }
            if (values[root] >= values[child]) {
                return;
            }
            double temporary = values[root];
            values[root] = values[child];
            values[child] = temporary;
            root = child;
        }
    }

    private static double notANumber() {
        return 0.0 / 0.0;
    }
}

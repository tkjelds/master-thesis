import java.util.Arrays;

@SecureMetricAggregator.ThreadSafe
public class SecureMetricAggregator {
    @interface ThreadSafe {}

    private static final double NaN = Double.NaN;
    private static final long MAX_VALUE = Long.MAX_VALUE;
    private static final int INITIAL_CAPACITY = 16;

    private Clock clock;
    private Batch[] batches;
    private int size;

    public SecureMetricAggregator(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("Clock cannot be null");
        }
        this.clock = clock;
        this.batches = new Batch[INITIAL_CAPACITY];
    }

    public void put(double[] measurements, long ttl) {
        if (measurements == null || measurements.length == 0 || ttl <= 0) {
            throw new IllegalArgumentException("Measurements cannot be null, empty, or nonpositive TTL");
        }
        double[] copy = Arrays.copyOf(measurements, measurements.length);
        long expiration = clock.now() + ttl;
        if (expiration >= MAX_VALUE) {
            throw new IllegalArgumentException("Timestamp overflow before mutation");
        }
        synchronized (this) {
            if (size == batches.length) {
                resize();
            }
            batches[size++] = new Batch(copy, expiration);
        }
    }

    public int removeExpired() {
        long now = clock.now();
        synchronized (this) {
            int removed = 0;
            for (int i = size - 1; i >= 0; i--) {
                if (batches[i].expiration <= now) {
                    removed += batches[i].measurements.length;
                    for (int j = i; j < size - 1; j++) {
                        batches[j] = batches[j + 1];
                    }
                    size--;
                } else {
                    break;
                }
            }
            return removed;
        }
    }

    public Statistics statistics() {
        synchronized (this) {
            removeExpired();
            double[] data = new double[size * 10];
            int index = 0;
            for (int i = 0; i < size; i++) {
                System.arraycopy(batches[i].measurements, 0, data, index, batches[i].measurements.length);
                index += batches[i].measurements.length;
            }
            return new Statistics(clock.now(), data);
        }
    }

    private void resize() {
        Batch[] newBatches = new Batch[batches.length * 2];
        System.arraycopy(batches, 0, newBatches, 0, size);
        batches = newBatches;
    }

    private static class Batch {
        final double[] measurements;
        final long expiration;

        Batch(double[] measurements, long expiration) {
            this.measurements = measurements;
            this.expiration = expiration;
        }
    }

    public static record Statistics(long observationTime, double count, double mean, double median, double variance) {}

    public static interface Clock {
        long now();
    }
}

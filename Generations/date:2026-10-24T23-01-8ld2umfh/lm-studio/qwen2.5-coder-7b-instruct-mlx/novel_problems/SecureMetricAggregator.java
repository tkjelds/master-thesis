@SecureMetricAggregator.ThreadSafe
public class SecureMetricAggregator {
    @interface ThreadSafe {}

    private static final double NaN = Double.NaN;
    private static final double INFINITY = Double.POSITIVE_INFINITY;

    private final Clock clock;
    private final Object lock = new Object();
    private Batch[] batches;

    public SecureMetricAggregator(Clock clock) {
        this.clock = clock;
        this.batches = new Batch[10];
    }

    public void put(double[] measurements, long ttl) {
        if (measurements == null || measurements.length == 0 || ttl <= 0) {
            throw new IllegalArgumentException("Invalid input");
        }
        double[] copy = measurements.clone();
        Batch batch = new Batch(copy, clock.now() + ttl);
        synchronized (lock) {
            ensureCapacity(1);
            batches[batchcount] = batch;
            ++batchcount;
        }
    }

    private void ensureCapacity(int additional) {
        if (batchcount + additional >= batches.length) {
            Batch[] newBatches = new Batch[batches.length * 2];
            System.arraycopy(batches, 0, newBatches, 0, batchcount);
            batches = newBatches;
        }
    }

    public long removeExpired() {
        long now = clock.now();
        synchronized (lock) {
            int newBatchcount = 0;
            for (int i = 0; i < batchcount; ++i) {
                if (batches[i].expiration <= now) {
                    remove(i);
                } else {
                    batches[newBatchcount++] = batches[i];
                }
            }
            batchcount = newBatchcount;
        }
        return removals;
    }

    private void remove(int index) {
        System.arraycopy(batches, index + 1, batches, index, batchcount - (index + 1));
        --batchcount;
    }

    public Statistics statistics() {
        long now = clock.now();
        synchronized (lock) {
            removeExpired();
            int size = batchcount * batchSize;
            double[] values = new double[size];
            int index = 0;
            for (int i = 0; i < batchcount; ++i) {
                System.arraycopy(batches[i].measurements, 0, values, index * batchSize, batchSize);
                index++;
            }
            return new Statistics(now, size, mean(values), median(values), variance(values));
        }
    }

    private double mean(double[] values) {
        double sum = 0;
        for (double value : values) {
            sum += value;
        }
        return values.length == 0 ? NaN : sum / values.length;
    }

    private double median(double[] values) {
        Arrays.sort(values);
        int mid = values.length / 2;
        return values.length % 2 == 0 ? (values[mid - 1] + values[mid]) / 2 : values[mid];
    }

    private double variance(double[] values) {
        double mean = mean(values);
        if (Double.isNaN(mean)) {
            return NaN;
        }
        double sum = 0;
        for (double value : values) {
            sum += Math.pow(value - mean, 2);
        }
        return values.length == 0 ? NaN : sum / values.length;
    }

    private static class Batch {
        final double[] measurements;
        final long expiration;

        Batch(double[] measurements, long expiration) {
            this.measurements = measurements;
            this.expiration = expiration;
        }
    }

    private static class Statistics {
        final long observationTime;
        final int count;
        final double mean;
        final double median;
        final double variance;

        Statistics(long observationTime, int count, double mean, double median, double variance) {
            this.observationTime = observationTime;
            this.count = count;
            this.mean = mean;
            this.median = median;
            this.variance = variance;
        }
    }

    private int batchcount = 0;
    private long removals = 0;
}

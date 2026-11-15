@SecureMetricAggregator.ThreadSafe
public class SecureMetricAggregator {
    @interface ThreadSafe {}

    private final Clock clock;
    private final Object lock = new Object();
    private Batch[] batches;
    private int size;

    public SecureMetricAggregator(Clock clock) {
        this.clock = clock;
        this.batches = new Batch[10];
    }

    public void put(double[] measurements, long ttl) {
        synchronized (lock) {
            if (measurements == null || measurements.length == 0 || ttl <= 0) {
                throw new IllegalArgumentException();
            }
            long expiration = clock.now() + ttl;
            if (expiration < 0) {
                throw new RuntimeException("Timestamp overflow");
            }
            Batch batch = new Batch(measurements, expiration);
            ensureCapacity();
            batches[size++] = batch;
        }
    }

    public int removeExpired() {
        synchronized (lock) {
            long now = clock.now();
            int removed = 0;
            for (int i = 0; i < size; ) {
                if (batches[i].expiration <= now) {
                    removed += batches[i].measurements.length;
                    for (int j = i + 1; j < size; j++) {
                        batches[j - 1] = batches[j];
                    }
                    size--;
                } else {
                    i++;
                }
            }
            return removed;
        }
    }

    public Statistics statistics() {
        synchronized (lock) {
            removeExpired();
            long capturedTime = clock.now();
            double[] values = new double[size * 10];
            int index = 0;
            for (int i = 0; i < size; i++) {
                for (double value : batches[i].measurements) {
                    values[index++] = value;
                }
            }
            return new Statistics(capturedTime, index, values);
        }
    }

    private void ensureCapacity() {
        if (size == batches.length) {
            Batch[] newBatches = new Batch[batches.length * 2];
            System.arraycopy(batches, 0, newBatches, 0, size);
            batches = newBatches;
        }
    }

    private static class Batch {
        final double[] measurements;
        final long expiration;

        Batch(double[] measurements, long expiration) {
            this.measurements = measurements;
            this.expiration = expiration;
        }
    }

    public static final class Statistics {
        private final long observationTime;
        private final int count;
        private final double mean;
        private final double median;
        private final double variance;

        Statistics(long observationTime, int count, double[] values) {
            this.observationTime = observationTime;
            this.count = count;
            if (count == 0) {
                mean = Double.NaN;
                median = Double.NaN;
                variance = Double.NaN;
            } else {
                mean = calculateMean(values);
                Arrays.sort(values, 0, count);
                median = count % 2 == 0 ? (values[count / 2 - 1] + values[count / 2]) / 2 : values[count / 2];
                variance = calculateVariance(values, mean);
            }
        }

        private double calculateMean(double[] values) {
            double sum = 0;
            for (double value : values) {
                sum += value;
            }
            return sum / count;
        }

        private double calculateVariance(double[] values, double mean) {
            double sumOfSquares = 0;
            for (double value : values) {
                sumOfSquares += Math.pow(value - mean, 2);
            }
            return sumOfSquares / count;
        }

        public long observationTime() {
            return observationTime;
        }

        public int count() {
            return count;
        }

        public double mean() {
            return mean;
        }

        public double median() {
            return median;
        }

        public double variance() {
            return variance;
        }
    }
}

import java.util.Arrays;

@SecureMetricAggregator.ThreadSafe
class SecureMetricAggregator {
    @interface ThreadSafe {}

    private static class Batch {
        long expiration;
        double[] measurements;

        Batch(long expiration, double[] measurements) {
            this.expiration = expiration;
            this.measurements = Arrays.copyOf(measurements, measurements.length);
        }
    }

    private final Clock clock;
    private final Object lock = new Object();
    private Batch[] batches;

    SecureMetricAggregator(Clock clock, int initialCapacity) {
        this.clock = clock;
        this.batches = new Batch[initialCapacity];
    }

    public void put(double[] measurements, long ttl) {
        if (measurements == null || measurements.length == 0 || ttl <= 0) {
            throw new IllegalArgumentException("Invalid input");
        }

        long expiration = clock.now() + ttl;
        if (expiration < 0) {
            throw new IllegalArgumentException("Timestamp overflow");
        }

        synchronized (lock) {
            pushBatch(new Batch(expiration, measurements));
        }
    }

    public int removeExpired() {
        synchronized (lock) {
            long now = clock.now();
            int removedCount = 0;
            for (int i = 0; i < batches.length; ) {
                if (batches[i].expiration <= now) {
                    removedCount += batches[i].measurements.length;
                    System.arraycopy(batches, i + 1, batches, i, batches.length - (i + 1));
                    batches = Arrays.copyOf(batches, batches.length - 1);
                } else {
                    i++;
                }
            }
            return removedCount;
        }
    }

    public Statistics statistics() {
        synchronized (lock) {
            long now = clock.now();
            removeExpired();
            double[] allMeasurements = new double[batches.length];
            int index = 0;
            for (Batch batch : batches) {
                System.arraycopy(batch.measurements, 0, allMeasurements, index, batch.measurements.length);
                index += batch.measurements.length;
            }

            if (allMeasurements.length == 0) {
                return new Statistics(clock.now(), 0, Double.NaN, Double.NaN, Double.NaN);
            }

            double sum = 0;
            double sumOfSquares = 0;
            for (double value : allMeasurements) {
                sum += value;
                sumOfSquares += value * value;
            }

            double mean = sum / allMeasurements.length;
            Arrays.sort(allMeasurements);
            double median = (allMeasurements.length % 2 == 0) ?
                    (allMeasurements[allMeasurements.length / 2 - 1] + allMeasurements[allMeasurements.length / 2]) / 2 :
                    allMeasurements[allMeasurements.length / 2];
            double variance = (sumOfSquares - sum * sum / allMeasurements.length) / allMeasurements.length;

            return new Statistics(clock.now(), allMeasurements.length, mean, median, variance);
        }
    }

    private void pushBatch(Batch batch) {
        for (int i = 0; i < batches.length; i++) {
            if (batches[i] == null) {
                batches[i] = batch;
                return;
            }
        }

        Batch[] newBatches = Arrays.copyOf(batches, batches.length * 2);
        newBatches[newBatches.length - 1] = batch;
        batches = newBatches;
    }
}

@Clock.ThreadSafe
class Clock {
    @interface ThreadSafe {}

    public long now() {
        return System.nanoTime();
    }
}

@Statistics.ThreadSafe
final class Statistics {
    @interface ThreadSafe {}

    private final long observationTime;
    private final int count;
    private final double mean;
    private final double median;
    private final double variance;

    Statistics(long observationTime, int count, double mean, double median, double variance) {
        this.observationTime = observationTime;
        this.count = count;
        this.mean = mean;
        this.median = median;
        this.variance = variance;
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

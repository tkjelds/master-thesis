```java
import java.util.Arrays;

class Clock {
    long now();
}

final class Statistics {
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

    @Override
    public String toString() {
        return "Statistics{" +
                "observationTime=" + observationTime +
                ", count=" + count +
                ", mean=" + mean +
                ", median=" + median +
                ", variance=" + variance +
                '}';
    }
}

final class SecureMetricAggregator {
    private final Clock clock;
    private final Object lock = new Object();
    private Batch[] batches = new Batch[1];
    private int size = 0;

    SecureMetricAggregator(Clock clock) {
        this.clock = clock;
    }

    void put(double[] measurements, long ttl) {
        synchronized (lock) {
            if (measurements == null || measurements.length == 0 || ttl <= 0) {
                throw new IllegalArgumentException("Invalid measurements or TTL");
            }
            long expiration = clock.now() + ttl;
            if (expiration < 0) {
                throw new ArithmeticException("Timestamp overflow");
            }
            for (double measurement : measurements) {
                if (!Double.isFinite(measurement)) {
                    throw new IllegalArgumentException("Invalid measurement");
                }
            }
            ensureCapacity(size + 1);
            batches[size++] = new Batch(expiration, measurements.clone());
        }
    }

    int removeExpired() {
        synchronized (lock) {
            long now = clock.now();
            int removedCount = 0;
            for (int i = 0; i < size; ) {
                if (batches[i].expiration <= now) {
                    removedCount += batches[i].measurements.length;
                    for (int j = i; j < size - 1; j++) {
                        batches[j] = batches[j + 1];
                    }
                    size--;
                } else {
                    i++;
                }
            }
            return removedCount;
        }
    }

    Statistics statistics() {
        synchronized (lock) {
            removeExpired();
            int count = 0;
            double sum = 0.0;
            for (int i = 0; i < size; i++) {
                count += batches[i].measurements.length;
                for (double measurement : batches[i].measurements) {
                    sum += measurement;
                }
            }
            double mean = count == 0 ? Double.NaN : sum / count;
            double[] values = new double[count];
            int index = 0;
            for (int i = 0; i < size; i++) {
                System.arraycopy(batches[i].measurements, 0, values, index, batches[i].measurements.length);
                index += batches[i].measurements.length;
            }
            Arrays.sort(values);
            double median = count == 0 ? Double.NaN : count % 2 == 1 ?
                    values[count / 2] :
                    (values[count / 2 - 1] + values[count / 2]) / 2.0;
            double variance = count == 0 ? Double.NaN : sumOfSquares(values, mean) / (count - 1);
            return new Statistics(clock.now(), count, mean, median, variance);
        }
    }

    private void ensureCapacity(int capacity) {
        if (capacity > batches.length) {
            Batch[] newBatches = new Batch[capacity * 2];
            System.arraycopy(batches, 0, newBatches, 0, size);
            batches = newBatches;
        }
    }

    private static double sumOfSquares(double[] values, double mean) {
        double sum = 0.0;
        for (double value : values) {
            sum += Math.pow(value - mean, 2);
        }
        return sum;
    }

    private static class Batch {
        final long expiration;
        final double[] measurements;

        Batch(long expiration, double[] measurements) {
            this.expiration = expiration;
            this.measurements = measurements.clone();
        }
    }
}
```

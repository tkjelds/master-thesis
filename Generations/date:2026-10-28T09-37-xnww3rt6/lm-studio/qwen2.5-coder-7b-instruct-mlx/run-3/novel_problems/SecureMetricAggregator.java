```java
class Clock {
    long now() { return System.nanoTime(); } // Simplified for example purposes
}

class SecureMetricAggregator {
    private static final class Batch {
        final long expiration;
        final double[] values;

        Batch(long expiration, double[] values) {
            this.expiration = expiration;
            this.values = values.clone(); // Copy to avoid external mutation
        }
    }

    private final Clock clock;
    private final Object lock = new Object();
    private List<Batch> batches = new ArrayList<>();

    SecureMetricAggregator(Clock clock) {
        this.clock = clock;
    }

    void put(double[] measurements, long ttl) {
        synchronized (lock) {
            checkInputs(measurements, ttl);
            long expiration = now() + ttl;
            batches.add(new Batch(expiration, measurements));
        }
    }

    long removeExpired() {
        synchronized (lock) {
            long now = this.now();
            int removedCount = 0;
            for (Iterator<Batch> it = batches.iterator(); it.hasNext(); ) {
                if (it.next().expiration <= now) {
                    removedCount += it.next().values.length;
                    it.remove();
                }
            }
            return removedCount;
        }
    }

    Statistics statistics() {
        synchronized (lock) {
            long now = this.now();
            removeExpired(); // Ensure no expired batches are included
            double[] values = new double[0];
            for (Batch batch : batches) {
                if (batch.expiration > now) {
                    values = concat(values, batch.values);
                }
            }
            return new Statistics(now, values.length, mean(values), median(values), variance(values));
        }
    }

    private static void checkInputs(double[] measurements, long ttl) {
        if (measurements == null || measurements.length == 0) {
            throw new IllegalArgumentException("Measurements cannot be null or empty");
        }
        for (double value : measurements) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Measurements cannot be NaN or infinite");
            }
        }
        if (ttl <= 0) {
            throw new IllegalArgumentException("TTL must be positive");
        }
    }

    private static long now() {
        return Clock.getInstance().now();
    }

    private static double[] concat(double[] a, double[] b) {
        double[] result = new double[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    private static double mean(double[] values) {
        if (values.length == 0) {
            return Double.NaN;
        }
        double sum = 0.0;
        for (double value : values) {
            sum += value;
        }
        return sum / values.length;
    }

    private static double median(double[] values) {
        if (values.length == 0) {
            return Double.NaN;
        }
        Arrays.sort(values);
        int middle = values.length / 2;
        if (values.length % 2 == 0) {
            return (values[middle - 1] + values[middle]) / 2.0;
        } else {
            return values[middle];
        }
    }

    private static double variance(double[] values) {
        if (values.length == 0) {
            return Double.NaN;
        }
        double mean = mean(values);
        double sumOfSquaredDifferences = 0.0;
        for (double value : values) {
            sumOfSquaredDifferences += Math.pow(value - mean, 2);
        }
        return sumOfSquaredDifferences / values.length;
    }

    static class Statistics {
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

    // Clock implementation
    static class ClockInstanceHolder {
        private static final Clock INSTANCE = new Clock();
    }
    public static Clock getInstance() {
        return ClockInstanceHolder.INSTANCE;
    }
}
```

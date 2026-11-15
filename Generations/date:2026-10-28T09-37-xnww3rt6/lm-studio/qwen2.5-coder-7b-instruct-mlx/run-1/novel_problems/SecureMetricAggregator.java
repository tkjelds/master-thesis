@Clock.ThreadSafe
class Clock {
    @interface ThreadSafe {}

    long now() {
        return System.currentTimeMillis();
    }
}

@SecureMetricAggregator.ThreadSafe
class SecureMetricAggregator {
    @interface ThreadSafe {}

    private final Clock clock;
    private final Object lock = new Object();
    private Batch[] batches;

    SecureMetricAggregator(Clock clock) {
        this.clock = clock;
        this.batches = new Batch[16];
    }

    void put(double[] measurements, long ttl) {
        if (measurements == null || measurements.length == 0) {
            return;
        }
        for (double measurement : measurements) {
            if (Double.isNaN(measurement) || Double.isInfinite(measurement)) {
                return;
            }
        }
        if (ttl <= 0) {
            return;
        }
        synchronized (lock) {
            if (ttl + clock.now() < 0) {
                throw new ArithmeticException("Timestamp overflow");
            }
            long expiration = ttl + clock.now();
            addBatch(expiration, measurements.clone());
        }
    }

    private void addBatch(long expiration, double[] values) {
        Batch newBatch = new Batch(expiration, values);
        int i = 0;
        while (i < batches.length && batches[i] != null) {
            i++;
        }
        if (i == batches.length) {
            Batch[] newBatches = new Batch[batches.length * 2];
            System.arraycopy(batches, 0, newBatches, 0, batches.length);
            batches = newBatches;
        }
        batches[i] = newBatch;
    }

    int removeExpired() {
        synchronized (lock) {
            long now = clock.now();
            int removed = 0;
            for (int i = 0; i < batches.length; ) {
                if (batches[i] != null && batches[i].expiration <= now) {
                    removed += batches[i].values.length;
                    System.arraycopy(batches, i + 1, batches, i, batches.length - i - 1);
                    batches[batches.length - 1] = null;
                } else {
                    i++;
                }
            }
            return removed;
        }
    }

    Statistics statistics() {
        synchronized (lock) {
            removeExpired();
            long observationTime = clock.now();
            int count = 0;
            double sum = 0.0;
            double[] values = new double[128];
            for (Batch batch : batches) {
                if (batch != null) {
                    for (double value : batch.values) {
                        values[count] = value;
                        sum += value;
                        count++;
                    }
                }
            }
            if (count == 0) {
                return new Statistics(observationTime, 0, Double.NaN, Double.NaN, Double.NaN);
            }
            double mean = sum / count;
            Arrays.sort(values, 0, count);
            double median = (count % 2 == 1) ? values[count / 2] : (values[(count / 2) - 1] + values[count / 2]) / 2.0;
            double variance = 0.0;
            for (int i = 0; i < count; i++) {
                variance += Math.pow(values[i] - mean, 2);
            }
            variance /= count;
            return new Statistics(observationTime, count, mean, median, variance);
        }
    }

    private static class Batch {
        final long expiration;
        final double[] values;

        Batch(long expiration, double[] values) {
            this.expiration = expiration;
            this.values = values.clone();
        }
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
}

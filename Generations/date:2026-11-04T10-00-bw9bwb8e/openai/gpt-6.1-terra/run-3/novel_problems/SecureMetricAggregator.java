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
            double variance) {
    }

    private static final class Batch {
        final long expiration;
        final double[] values;
        Batch next;

        Batch(long expiration, double[] values, Batch next) {
            this.expiration = expiration;
            this.values = values;
            this.next = next;
        }
    }

    private static final class Snapshot {
        final double[] values;
        final Snapshot next;

        Snapshot(double[] values, Snapshot next) {
            this.values = values;
            this.next = next;
        }
    }

    private final Clock clock;
    private final Object monitor = new Object();
    private Batch batches;
    private long valueCount;

    public SecureMetricAggregator(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.clock = clock;
    }

    public void put(double[] measurements, long ttl) {
        if (measurements == null || measurements.length == 0) {
            throw new IllegalArgumentException("measurements must not be null or empty");
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

        synchronized (monitor) {
            long now = clock.now();
            if (now > Long.MAX_VALUE - ttl) {
                throw new IllegalArgumentException("expiration timestamp overflow");
            }
            if (valueCount > Long.MAX_VALUE - copy.length) {
                throw new IllegalStateException("measurement count overflow");
            }

            Batch batch = new Batch(now + ttl, copy, batches);
            batches = batch;
            valueCount += copy.length;
        }
    }

    public long removeExpired() {
        synchronized (monitor) {
            return purgeExpired(clock.now());
        }
    }

    public Statistics statistics() {
        final long observationTime;
        final long count;
        Snapshot snapshot = null;

        synchronized (monitor) {
            observationTime = clock.now();
            purgeExpired(observationTime);
            count = valueCount;

            for (Batch batch = batches; batch != null; batch = batch.next) {
                double[] copy = new double[batch.values.length];
                for (int i = 0; i < copy.length; i++) {
                    copy[i] = batch.values[i];
                }
                snapshot = new Snapshot(copy, snapshot);
            }
        }

        if (count == 0) {
            return new Statistics(
                    observationTime, 0, Double.NaN, Double.NaN, Double.NaN);
        }

        double scale = 0.0;
        for (Snapshot part = snapshot; part != null; part = part.next) {
            for (double value : part.values) {
                double magnitude = Math.abs(value);
                if (magnitude > scale) {
                    scale = magnitude;
                }
            }
        }

        double mean;
        double variance;
        if (scale == 0.0) {
            mean = 0.0;
            variance = 0.0;
        } else {
            double sum = 0.0;
            double correction = 0.0;
            for (Snapshot part = snapshot; part != null; part = part.next) {
                for (double value : part.values) {
                    double term = value / scale;
                    double next = sum + term;
                    if (Math.abs(sum) >= Math.abs(term)) {
                        correction += (sum - next) + term;
                    } else {
                        correction += (term - next) + sum;
                    }
                    sum = next;
                }
            }

            double normalizedMean = (sum + correction) / (double) count;
            if (normalizedMean > 1.0) {
                normalizedMean = 1.0;
            } else if (normalizedMean < -1.0) {
                normalizedMean = -1.0;
            }
            mean = normalizedMean * scale;

            double squareSum = 0.0;
            double squareCorrection = 0.0;
            for (Snapshot part = snapshot; part != null; part = part.next) {
                for (double value : part.values) {
                    double difference = value - mean;
                    double normalizedDifference;
                    if (Double.isInfinite(difference)) {
                        normalizedDifference = value / scale - mean / scale;
                    } else {
                        normalizedDifference = difference / scale;
                    }

                    double term = normalizedDifference * normalizedDifference;
                    double next = squareSum + term;
                    if (Math.abs(squareSum) >= term) {
                        squareCorrection += (squareSum - next) + term;
                    } else {
                        squareCorrection += (term - next) + squareSum;
                    }
                    squareSum = next;
                }
            }

            double normalizedVariance = (squareSum + squareCorrection) / (double) count;
            double standardDeviation = Math.sqrt(normalizedVariance) * scale;
            variance = standardDeviation * standardDeviation;
        }

        double upperMiddle = select(snapshot, count / 2);
        double median;
        if ((count & 1L) != 0) {
            median = upperMiddle;
        } else {
            double lowerMiddle = select(snapshot, count / 2 - 1);
            if (lowerMiddle <= 0.0 && upperMiddle >= 0.0) {
                median = (lowerMiddle + upperMiddle) / 2.0;
            } else {
                median = lowerMiddle + (upperMiddle - lowerMiddle) / 2.0;
            }
        }

        return new Statistics(observationTime, count, mean, median, variance);
    }

    // Called only while holding monitor.
    private long purgeExpired(long now) {
        long removed = 0;
        Batch previous = null;
        Batch current = batches;

        while (current != null) {
            if (current.expiration <= now) {
                removed += current.values.length;
                if (previous == null) {
                    batches = current.next;
                } else {
                    previous.next = current.next;
                }
                current = current.next;
            } else {
                previous = current;
                current = current.next;
            }
        }

        valueCount -= removed;
        return removed;
    }

    // Select by the unsigned ordering of transformed IEEE 754 bit patterns.
    // This operates exclusively on detached snapshot arrays.
    private static double select(Snapshot snapshot, long rank) {
        long prefix = 0;
        long prefixMask = 0;

        for (long bit = Long.MIN_VALUE; bit != 0; bit >>>= 1) {
            long zeros = 0;
            for (Snapshot part = snapshot; part != null; part = part.next) {
                for (double value : part.values) {
                    long bits = Double.doubleToRawLongBits(value);
                    long key = bits < 0 ? ~bits : bits ^ Long.MIN_VALUE;
                    if ((key & prefixMask) == prefix && (key & bit) == 0) {
                        zeros++;
                    }
                }
            }

            prefixMask |= bit;
            if (rank >= zeros) {
                rank -= zeros;
                prefix |= bit;
            }
        }

        long bits = prefix < 0 ? prefix ^ Long.MIN_VALUE : ~prefix;
        return Double.longBitsToDouble(bits);
    }
}

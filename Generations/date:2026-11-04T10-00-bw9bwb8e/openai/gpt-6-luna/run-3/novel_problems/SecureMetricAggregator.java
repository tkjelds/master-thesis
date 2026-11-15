@SecureMetricAggregator.ThreadSafe
public class SecureMetricAggregator {
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

    private static final class Batch {
        private final double[] values;
        private final long expiration;
        private Batch next;

        private Batch(double[] values, long expiration, Batch next) {
            this.values = values;
            this.expiration = expiration;
            this.next = next;
        }
    }

    private final Clock clock;
    private final Object monitor = new Object();
    private Batch head;

    public SecureMetricAggregator(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
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

        double[] copy = measurements.clone();
        for (double value : copy) {
            if (value != value
                    || value > 1.7976931348623157E308
                    || value < -1.7976931348623157E308) {
                throw new IllegalArgumentException("measurements must be finite");
            }
        }

        long now = clock.now();
        if (now > 9223372036854775807L - ttl) {
            throw new IllegalArgumentException("expiration timestamp overflows");
        }
        long expiration = now + ttl;

        synchronized (monitor) {
            head = new Batch(copy, expiration, head);
        }
    }

    public long removeExpired() {
        long observationTime = clock.now();
        synchronized (monitor) {
            return purgeExpired(observationTime);
        }
    }

    public Statistics statistics() {
        long observationTime = clock.now();
        double[] snapshot;

        synchronized (monitor) {
            purgeExpired(observationTime);

            long total = 0;
            for (Batch batch = head; batch != null; batch = batch.next) {
                if (total > 9223372036854775807L - batch.values.length) {
                    throw new IllegalStateException("too many measurements to snapshot");
                }
                total += batch.values.length;
            }
            if (total > 2147483647L) {
                throw new IllegalStateException("too many measurements to snapshot");
            }

            snapshot = new double[(int) total];
            int index = 0;
            for (Batch batch = head; batch != null; batch = batch.next) {
                for (double value : batch.values) {
                    snapshot[index++] = value;
                }
            }
        }

        if (snapshot.length == 0) {
            return new Statistics(observationTime, 0, Double.NaN, Double.NaN, Double.NaN);
        }

        double scale = 0.0;
        for (double value : snapshot) {
            double magnitude = value < 0.0 ? -value : value;
            if (magnitude > scale) {
                scale = magnitude;
            }
        }

        double normalizedMean = 0.0;
        double normalizedVariance = 0.0;
        if (scale != 0.0) {
            double sum = 0.0;
            double compensation = 0.0;
            for (double value : snapshot) {
                double term = value / scale;
                double adjusted = term - compensation;
                double next = sum + adjusted;
                compensation = (next - sum) - adjusted;
                sum = next;
            }
            normalizedMean = sum / snapshot.length;
            if (normalizedMean > 1.0) {
                normalizedMean = 1.0;
            } else if (normalizedMean < -1.0) {
                normalizedMean = -1.0;
            }

            sum = 0.0;
            compensation = 0.0;
            for (double value : snapshot) {
                double difference = value / scale - normalizedMean;
                double term = difference * difference;
                double adjusted = term - compensation;
                double next = sum + adjusted;
                compensation = (next - sum) - adjusted;
                sum = next;
            }
            normalizedVariance = sum / snapshot.length;
            if (normalizedVariance < 0.0) {
                normalizedVariance = 0.0;
            } else if (normalizedVariance > 1.0) {
                normalizedVariance = 1.0;
            }
        }

        double mean = normalizedMean * scale;
        double variance = (normalizedVariance * scale) * scale;

        sort(snapshot);
        int middle = snapshot.length / 2;
        double median;
        if ((snapshot.length & 1) != 0) {
            median = snapshot[middle];
        } else {
            double lower = snapshot[middle - 1];
            double upper = snapshot[middle];
            if ((lower <= 0.0 && upper >= 0.0)
                    || (lower >= 0.0 && upper <= 0.0)) {
                median = lower * 0.5 + upper * 0.5;
            } else {
                median = lower + (upper - lower) * 0.5;
            }
        }

        return new Statistics(snapshot.length, snapshot.length, mean, median, variance);
    }

    private long purgeExpired(long observationTime) {
        long removed = 0;
        Batch previous = null;
        Batch current = head;

        while (current != null) {
            if (current.expiration <= observationTime) {
                removed += current.values.length;
                if (previous == null) {
                    head = current.next;
                } else {
                    previous.next = current.next;
                }
            } else {
                previous = current;
            }
            current = current.next;
        }
        return removed;
    }

    private static void sort(double[] values) {
        int length = values.length;
        for (int start = length / 2 - 1; start >= 0; start--) {
            siftDown(values, start, length);
        }
        for (int end = length - 1; end > 0; end--) {
            double temporary = values[0];
            values[0] = values[end];
            values[end] = temporary;
            siftDown(values, 0, end);
        }
    }

    private static void siftDown(double[] values, int root, int end) {
        while (root < end / 2) {
            int child = root * 2 + 1;
            if (child + 1 < end && values[child] < values[child + 1]) {
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
}

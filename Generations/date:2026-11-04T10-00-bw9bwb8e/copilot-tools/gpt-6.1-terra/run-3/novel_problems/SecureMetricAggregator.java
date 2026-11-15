@SecureMetricAggregator.ThreadSafe
public final class SecureMetricAggregator {
    @interface ThreadSafe {}

    public interface Clock {
        long now();
    }

    public record Statistics(
            long observationTime, long count, double mean,
            double median, double variance) {
    }

    private static final long MAX_COUNT = 0x7fffffffffffffffL;

    private static final class Batch {
        final double[] values;
        final long expiration;
        Batch next;

        Batch(double[] values, long expiration, Batch next) {
            this.values = values;
            this.expiration = expiration;
            this.next = next;
        }
    }

    private static final class Value {
        final double number;
        Value next;

        Value(double number) {
            this.number = number;
        }
    }

    private final Object monitor = new Object();
    private final Clock clock;
    private Batch batches;
    private long count;

    public SecureMetricAggregator(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.clock = clock;
    }

    public void put(double[] measurements, long ttl) {
        if (measurements == null || measurements.length == 0 || ttl <= 0) {
            throw new IllegalArgumentException("invalid measurements or ttl");
        }
        double[] copy = new double[measurements.length];
        for (int i = 0; i < copy.length; i++) {
            double value = measurements[i];
            if (value - value != 0.0) {
                throw new IllegalArgumentException("measurements must be finite");
            }
            copy[i] = value;
        }

        synchronized (monitor) {
            long now = clock.now();
            if (ttl > MAX_COUNT - now) {
                throw new IllegalArgumentException("expiration timestamp overflow");
            }
            // The structure does not block; exceeding its count capacity fails.
            if (copy.length > MAX_COUNT - count) {
                throw new IllegalStateException("measurement count overflow");
            }
            batches = new Batch(copy, now + ttl, batches);
            count += copy.length;
        }
    }

    public long removeExpired() {
        synchronized (monitor) {
            return purge(clock.now());
        }
    }

    public Statistics statistics() {
        long time;
        long snapshotCount;
        Value head = null;
        Value tail = null;
        synchronized (monitor) {
            time = clock.now();
            purge(time);
            snapshotCount = count;
            for (Batch batch = batches; batch != null; batch = batch.next) {
                for (double number : batch.values) {
                    Value value = new Value(number);
                    if (tail == null) {
                        head = value;
                    } else {
                        tail.next = value;
                    }
                    tail = value;
                }
            }
        }

        if (snapshotCount == 0) {
            double nan = 0.0 / 0.0;
            return new Statistics(time, 0, nan, nan, nan);
        }

        head = sort(head);
        double scale = 0.0;
        double lower = 0.0;
        double upper = 0.0;
        long index = 0;
        long lowerIndex = (snapshotCount - 1) / 2;
        long upperIndex = snapshotCount / 2;
        for (Value value = head; value != null; value = value.next) {
            double magnitude = absolute(value.number);
            if (magnitude > scale) {
                scale = magnitude;
            }
            if (index == lowerIndex) {
                lower = value.number;
            }
            if (index == upperIndex) {
                upper = value.number;
            }
            index++;
        }

        double median = midpoint(lower, upper);
        if (scale == 0.0) {
            return new Statistics(time, snapshotCount, 0.0, median, 0.0);
        }

        double sum = 0.0;
        double correction = 0.0;
        double directSum = 0.0;
        double directCorrection = 0.0;
        boolean directUsable = true;
        for (Value value = head; value != null; value = value.next) {
            double term = value.number / scale;
            double next = sum + term;
            if (absolute(sum) >= absolute(term)) {
                correction += (sum - next) + term;
            } else {
                correction += (term - next) + sum;
            }
            sum = next;
            if (directUsable) {
                double directTerm = value.number / snapshotCount;
                double directNext = directSum + directTerm;
                if ((value.number != 0.0 && absolute(directTerm) < 0x1.0p-1022)
                        || directNext - directNext != 0.0) {
                    directUsable = false;
                } else {
                    if (absolute(directSum) >= absolute(directTerm)) {
                        directCorrection += (directSum - directNext) + directTerm;
                    } else {
                        directCorrection += (directTerm - directNext) + directSum;
                    }
                    directSum = directNext;
                }
            }
        }
        double normalizedMean = (sum + correction) / snapshotCount;
        if (normalizedMean > 1.0) {
            normalizedMean = 1.0;
        } else if (normalizedMean < -1.0) {
            normalizedMean = -1.0;
        }
        double mean = normalizedMean * scale;
        if (directUsable) {
            double directMean = directSum + directCorrection;
            if (directMean - directMean == 0.0) {
                mean = directMean;
            }
        }

        double squares = 0.0;
        double squareCorrection = 0.0;
        double differenceSum = 0.0;
        double differenceCorrection = 0.0;
        for (Value value = head; value != null; value = value.next) {
            double difference = value.number / scale - normalizedMean;
            double term = difference * difference;
            double adjusted = term - squareCorrection;
            double next = squares + adjusted;
            squareCorrection = (next - squares) - adjusted;
            squares = next;
            adjusted = difference - differenceCorrection;
            next = differenceSum + adjusted;
            differenceCorrection = (next - differenceSum) - adjusted;
            differenceSum = next;
        }
        double averageDifference = differenceSum / snapshotCount;
        double normalizedVariance =
                squares / snapshotCount - averageDifference * averageDifference;
        if (normalizedVariance < 0.0) {
            normalizedVariance = 0.0;
        }
        double variance = scale * (scale * normalizedVariance);
        return new Statistics(time, snapshotCount, mean, median, variance);
    }

    private long purge(long now) {
        long removed = 0;
        Batch previous = null;
        Batch current = batches;
        while (current != null) {
            Batch next = current.next;
            if (current.expiration <= now) {
                removed += current.values.length;
                if (previous == null) {
                    batches = next;
                } else {
                    previous.next = next;
                }
            } else {
                previous = current;
            }
            current = next;
        }
        count -= removed;
        return removed;
    }

    private static double absolute(double number) {
        return number < 0.0 ? -number : number;
    }

    private static double midpoint(double left, double right) {
        double sum = left + right;
        if (sum - sum == 0.0) {
            return sum / 2.0;
        }
        return left / 2.0 + right / 2.0;
    }

    private static Value sort(Value head) {
        Value[] runs = new Value[63];
        while (head != null) {
            Value run = head;
            head = head.next;
            run.next = null;
            int level = 0;
            while (runs[level] != null) {
                run = merge(runs[level], run);
                runs[level] = null;
                level++;
            }
            runs[level] = run;
        }
        Value sorted = null;
        for (Value run : runs) {
            if (run != null) {
                sorted = merge(run, sorted);
            }
        }
        return sorted;
    }

    private static Value merge(Value left, Value right) {
        Value head = null;
        Value tail = null;
        while (left != null && right != null) {
            Value next;
            if (left.number <= right.number) {
                next = left;
                left = left.next;
            } else {
                next = right;
                right = right.next;
            }
            if (tail == null) {
                head = next;
            } else {
                tail.next = next;
            }
            tail = next;
        }
        Value remainder = left != null ? left : right;
        if (tail == null) {
            return remainder;
        }
        tail.next = remainder;
        return head;
    }
}

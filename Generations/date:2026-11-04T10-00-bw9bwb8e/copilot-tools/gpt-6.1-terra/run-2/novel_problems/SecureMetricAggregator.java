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

    private static final double MAX_FINITE = 1.7976931348623157E308;
    private static final double NAN = 0.0 / 0.0;
    private static final long MAX_COUNT = 9223372036854775807L;

    private final Object monitor = new Object();
    private final Clock clock;
    private Batch batches;
    private long count;

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

    private static final class Snapshot {
        double[] values;
        int index;
        Snapshot next;
        Snapshot parent;
        Snapshot left;
        Snapshot right;

        Snapshot(double[] values) {
            this.values = values;
        }
    }

    public SecureMetricAggregator(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.clock = clock;
    }

    /*
     * Storage is unbounded except by available memory and the long count
     * limit. Operations do not wait for capacity or for data to arrive.
     */
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
            if (value != value || value > MAX_FINITE || value < -MAX_FINITE) {
                throw new IllegalArgumentException("measurements must be finite");
            }
            copy[i] = value;
        }

        synchronized (monitor) {
            long now = clock.now();
            if (ttl > MAX_COUNT - now) {
                throw new IllegalArgumentException("expiration timestamp overflows");
            }
            if (copy.length > MAX_COUNT - count) {
                throw new IllegalStateException("measurement count overflows");
            }

            Batch batch = new Batch(copy, now + ttl, batches);
            batches = batch;
            count += copy.length;
        }
    }

    public long removeExpired() {
        synchronized (monitor) {
            return purge(clock.now());
        }
    }

    public Statistics statistics() {
        long observationTime;
        long snapshotCount;
        Snapshot snapshots = null;

        synchronized (monitor) {
            observationTime = clock.now();
            purge(observationTime);
            snapshotCount = count;

            for (Batch batch = batches; batch != null; batch = batch.next) {
                double[] copy = new double[batch.values.length];
                for (int i = 0; i < copy.length; i++) {
                    copy[i] = batch.values[i];
                }
                Snapshot snapshot = new Snapshot(copy);
                snapshot.next = snapshots;
                snapshots = snapshot;
            }
        }

        if (snapshotCount == 0) {
            return new Statistics(observationTime, 0, NAN, NAN, NAN);
        }

        double scale = 0.0;
        for (Snapshot snapshot = snapshots; snapshot != null; snapshot = snapshot.next) {
            for (double value : snapshot.values) {
                double magnitude = absolute(value);
                if (magnitude > scale) {
                    scale = magnitude;
                }
            }
        }

        double mean = 0.0;
        double variance = 0.0;
        if (scale != 0.0) {
            double sum = 0.0;
            double correction = 0.0;
            for (Snapshot snapshot = snapshots; snapshot != null; snapshot = snapshot.next) {
                for (double value : snapshot.values) {
                    double term = value / scale;
                    double updated = sum + term;
                    if (absolute(sum) >= absolute(term)) {
                        correction += (sum - updated) + term;
                    } else {
                        correction += (term - updated) + sum;
                    }
                    sum = updated;
                }
            }

            double normalizedMean = (sum + correction) / (double) snapshotCount;
            if (normalizedMean > 1.0) {
                normalizedMean = 1.0;
            } else if (normalizedMean < -1.0) {
                normalizedMean = -1.0;
            }
            mean = normalizedMean * scale;

            double squares = 0.0;
            double squaresCorrection = 0.0;
            for (Snapshot snapshot = snapshots; snapshot != null; snapshot = snapshot.next) {
                for (double value : snapshot.values) {
                    double difference = value - mean;
                    double normalizedDifference;
                    if (difference > MAX_FINITE || difference < -MAX_FINITE) {
                        normalizedDifference = value / scale - mean / scale;
                    } else {
                        normalizedDifference = difference / scale;
                    }

                    double term = normalizedDifference * normalizedDifference;
                    double updated = squares + term;
                    if (absolute(squares) >= absolute(term)) {
                        squaresCorrection += (squares - updated) + term;
                    } else {
                        squaresCorrection += (term - updated) + squares;
                    }
                    squares = updated;
                }
            }
            variance = ((squares + squaresCorrection) / (double) snapshotCount * scale) * scale;
        }

        double median = median(snapshots, snapshotCount);
        return new Statistics(observationTime, snapshotCount, mean, median, variance);
    }

    /* Called only while holding monitor. */
    private long purge(long observationTime) {
        long removed = 0;
        Batch previous = null;
        Batch current = batches;

        while (current != null) {
            if (current.expiration <= observationTime) {
                removed += current.values.length;
                if (previous == null) {
                    batches = current.next;
                } else {
                    previous.next = current.next;
                }
            } else {
                previous = current;
            }
            current = current.next;
        }

        count -= removed;
        return removed;
    }

    private static double absolute(double value) {
        return value < 0.0 ? -value : value;
    }

    private static double median(Snapshot snapshots, long valueCount) {
        Snapshot root = null;
        long heapSize = 0;

        for (Snapshot snapshot = snapshots; snapshot != null; snapshot = snapshot.next) {
            sort(snapshot.values);
            heapSize++;

            if (root == null) {
                root = snapshot;
            } else {
                Snapshot parent = nodeAt(root, heapSize / 2);
                snapshot.parent = parent;
                if ((heapSize & 1L) == 0) {
                    parent.left = snapshot;
                } else {
                    parent.right = snapshot;
                }

                Snapshot current = snapshot;
                while (current.parent != null
                        && currentValue(current) < currentValue(current.parent)) {
                    swapRuns(current, current.parent);
                    current = current.parent;
                }
            }
        }

        long lowerPosition = (valueCount - 1) / 2;
        long upperPosition = valueCount / 2;
        double lower = 0.0;

        for (long position = 0; position <= upperPosition; position++) {
            double value = currentValue(root);
            if (position == lowerPosition) {
                lower = value;
            }
            if (position == upperPosition) {
                if (lowerPosition == upperPosition) {
                    return value;
                }
                double sum = lower + value;
                if (sum > MAX_FINITE || sum < -MAX_FINITE) {
                    return lower / 2.0 + value / 2.0;
                }
                return sum / 2.0;
            }

            root.index++;
            if (root.index == root.values.length) {
                Snapshot last = nodeAt(root, heapSize);
                if (last == root) {
                    root = null;
                } else {
                    root.values = last.values;
                    root.index = last.index;
                    if (last.parent.left == last) {
                        last.parent.left = null;
                    } else {
                        last.parent.right = null;
                    }
                }
                heapSize--;
            }

            if (root != null) {
                siftDown(root);
            }
        }

        throw new IllegalStateException("invalid snapshot");
    }

    private static Snapshot nodeAt(Snapshot root, long position) {
        long bit = 1;
        while (bit <= position / 2) {
            bit <<= 1;
        }
        bit >>= 1;

        Snapshot current = root;
        while (bit != 0) {
            current = (position & bit) == 0 ? current.left : current.right;
            bit >>= 1;
        }
        return current;
    }

    private static double currentValue(Snapshot snapshot) {
        return snapshot.values[snapshot.index];
    }

    private static void swapRuns(Snapshot first, Snapshot second) {
        double[] values = first.values;
        int index = first.index;
        first.values = second.values;
        first.index = second.index;
        second.values = values;
        second.index = index;
    }

    private static void siftDown(Snapshot root) {
        Snapshot current = root;
        while (current.left != null) {
            Snapshot smaller = current.left;
            if (current.right != null
                    && currentValue(current.right) < currentValue(smaller)) {
                smaller = current.right;
            }
            if (currentValue(current) <= currentValue(smaller)) {
                return;
            }
            swapRuns(current, smaller);
            current = smaller;
        }
    }

    private static void sort(double[] values) {
        int length = values.length;
        for (int i = length / 2; i > 0; i--) {
            siftArray(values, i - 1, length);
        }
        for (int end = length - 1; end > 0; end--) {
            double value = values[0];
            values[0] = values[end];
            values[end] = value;
            siftArray(values, 0, end);
        }
    }

    private static void siftArray(double[] values, int root, int size) {
        while (root < size / 2) {
            int child = root * 2 + 1;
            if (child + 1 < size && values[child] < values[child + 1]) {
                child++;
            }
            if (values[root] >= values[child]) {
                return;
            }
            double value = values[root];
            values[root] = values[child];
            values[child] = value;
            root = child;
        }
    }
}

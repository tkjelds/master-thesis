public class SecureMetricAggregator {

    public interface Clock {
        long now();
    }

    private static class Batch {
        double[] values;
        long expiration;

        Batch(double[] data, long exp) {
            this.values = data;
            this.expiration = exp;
        }
    }

    public static class Statistics {
        private final long observationTime;
        private final long count;
        private final double mean;
        private final double median;
        private final double variance;

        private Statistics(long time, long count, double mean, double median, double var) {
            this.observationTime = time;
            this.count = count;
            this.mean = mean;
            this.median = median;
            this.variance = var;
        }

        public long getObservationTime() { return observationTime; }
        public long getCount() { return count; }
        public double getMean() { return mean; }
        public double getMedian() { return median; }
        public double getVariance() { return variance; }
    }

    private final Clock clock;
    private Batch[] batches;
    private int batchCount = 0;

    public SecureMetricAggregator(Clock clock) {
        if (clock == null) throw new IllegalArgumentException("Clock cannot be null");
        this.clock = clock;
        this.batches = new Batch[1]; // Start with a single capacity or dynamic growth is needed
    }

    // Since we cannot use ArrayList, we must manage an array manually.
    private static class ArrayList {
        private Batch[] elements;
        private int size;

        ArrayList() {
            elements = new Batch[1];
        }

        void add(Batch b) {
            if (size == elements.length) {
                Batch[] newArr = new Batch[elements.length * 2];
                for (int i = 0; i < elements.length; i++) {
                    newArr[i] = elements[i];
                }
                elements = newArr;
            }
            elements[size++] = b;
        }

        void removeExpired(long now) {
            int newSize = 0;
            for (int i = 0; i < size; i++) {
                if (elements[i].expiration > now) {
                    elements[newSize] = elements[i];
                } else {
                    // Logic for counting removed items happens in the caller's scope 
                }
            }
            // Wait, let's rethink the count logic. To support counting removed items correctly:
        }
    }

    // Manual array management to avoid library usage
    private Batch[] storage;
    private int size = 0;

    public SecureMetricAggregator(final Clock clk) {
        this.clock = clk;
        this.storage = new Batch[1];
    }

    public void put(double[] measurements, long ttl) {
        if (measurements == null || measurements.length == 0) throw new IllegalArgumentException();
        if (ttl <= 0) throw new IllegalArgumentException();

        for (double d : measurements) {
            if (Double.isNaN(d) || Double.isInfinite(d)) throw new IllegalArgumentException();
        }

        long now = clock.now();
        long expiry = now + ttl;
        if (expiry < 0) throw new ArithmeticException("Overflow");

        double[] copy = new double[measurements.length];
        System.arraycopy(measurements, 0, copy, 0, measurements.length);

        synchronized (this) {
            if (size == storage.length) {
                Batch[] newArr = new Batch[storage.length * 2];
                for (int i = 0; i < size; i++) {
                    newArr[i] = storage[i];
                }
                storage = newArr;
            }
            storage[size++] = new Batch(copy, expiry);
        }
    }

    public int removeExpired() {
        long now = clock.now();
        int removedCount = 0;
        synchronized (this) {
            for (int i = 0; i < size; i++) {
                if (storage[i].expiration <= now) {
                    removedCount += storage[i].values.length;
                    // Shift left
                    for (int j = i; j < size - 1; j++) {
                        storage[j] = storage[j + 1];
                    }
                    size--;
                    i--;
                }
            }
        }
        return removedCount;
    }

    public Statistics statistics() {
        long now = clock.now();
        double[] data;
        int totalCount = 0;

        synchronized (this) {
            // Remove expired first
            for (int i = 0; i < size; i++) {
                if (storage[i].expiration <= now) {
                    for (int j = 0; j < size - 1; j++) {
                        storage[j] = storage[i == 0 ? 1 : i - 1, i == 1 ? 0 : i - 1]; // No, incorrect indexing.
                    }
                }
            }
            // Re-implementing correctly:
            int writeIdx = 0;
            for (int i = 0; i < size; i++) {
                if (storage[i].expiration > now) {
                    // Keep it
                } else {
                    // Skip/remove handled by logic below
                }
            }
            // Let's restart the block for clarity.
            int activeSize = 0;
            for (int i = 0; i < size; i++) {
                if (storage[i].expiration > now) {
                    // We will re-process this. Let's use a temporary list logic.
                }
            }
        }
    }

    // Rewriting to follow the exact requirements strictly.
    private final Batch[] list;
    private int count;

    public SecureMetricAggregator(Clock clk) {
        this.clock = clk;
        this.list = new Batch[1];
    }

    // Re-declaring proper internal storage methods to be clean.
    private final Batch[] data;
    private int totalBatches = 0;

    public void put_fixed(double[] measurements, long ttl) {
        // Handled by logic below in final block.
    }
}

// Since I must provide a single file, let's re-write the complete logic properly.
// Restarting implementation inside the class block to ensure correctness.

class RealAggregator { } // Placeholder for mental reset
```

```java
public class SecureMetricAggregator {

    public interface Clock {
        long now();
    }

    public static class Statistics {
        private final long observationTime;
        private final long count;
        private final double mean;
        private final double median;
        private final double variance;

        public Statistics(long time, long count, double mean, double median, double var) {
            this.observationTime = time;
            this.count = count;
            this.mean = mean;
            this.median = median;
            this.variance = var;
        }

        public long getObservationTime() { return observation.time; } // wait, naming fix
    }

    // Proceeding with Final Implementation
}
```
(Self-Correction: I will provide the clean, correct implementation block now.)

```java
public class SecureMetricAggregator {

    public interface Clock {
        long now();
    }

    public static class Statistics {
        private final long observationTime;
        private final long count;
        private final double mean;
        private final double median;
        private final double variance;

        public Statistics(long observationTime, long count, double mean, double median, double variance) {
            this.observationTime = observationTime;
            this.count = count;
            this.mean = mean;
            this.median = median;
            this.variance = variance;
        }

        public long getObservationTime() { return observationTime; }
        public long getCount() { return count; }
        public double getMean() { return mean; }
        public double getMedian() { return median; }
        public double getVariance() { return variance; }
    }

    private static class Batch {
        double[] values;
        long expiration;
        Batch(double[] v, long e) { values = v; expiration = e; }
    }

    private final Clock clock;
    private Batch[] storage;
    private int size = 0;

    public SecureMetricAggregator(Clock clock) {
        if (clock == null) throw new IllegalArgumentException();
        this.clock = clock;
        this.storage = new Batch[1];
    }

    public void put(double[] measurements, long ttl) {
        if (measurements == null || measurements.length == 0) throw new IllegalArgumentException();
        if (ttl <= 0) throw new IllegalArgumentException();
        for (double d : measurements) {
            if (Double.isNaN(d) || Double.isInfinite(d)) throw new IllegalArgumentException();
        }

        long now = clock.now();
        long expiry = now + ttl;
        if (expiry < 0) throw new ArithmeticException();

        double[] copy = new double[measurements.length];
        System.arraycopy(measurements, 0, copy, 0, measurements.length);

        synchronized (this) {
            if (size == storage.length) {
                Batch[] newArr = new Batch[storage.length * 2];
                for (int i = 0; i < size; i++) newArr[i] = storage[i];
                storage = newArr;
            }
            storage[size++] = new Batch(copy, expiry);
        }
    }

    public int removeExpired() {
        long now = clock.now();
        int removedCount = 0;
        synchronized (this) {
            for (int i = 0; i < size; i++) {
                if (storage[i].expiration <= now) {
                    removedCount += storage[i].values.length;
                    for (int j = i; j < size - 1; j++) {
                        storage[j] = storage[j + 1];
                    }
                    size--;
                    i--;
                }
            }
        }
        return removedCount;
    }

    public Statistics statistics() {
        long now = clock.now();
        double[] allValues;
        int totalCount = 0;

        synchronized (this) {
            // Purge expired first
            for (int i = 0; i < size; i++) {
                if (storage[i].expiration <= now) {
                    for (int j = i; j < size - 1; j++) {
                        storage[j] = storage[j + 1];
                    }
                    size--;
                    i--;
                }
            }

            // Determine total count and collect data
            int count = 0;
            for (int i = 0; i < size; i++) {
                count += storage[i].values.length;
            }

            if (count == 0) {
                return new Statistics(now, 0, Double.NaN, Double.NaN, Double.NaN);
            }

            allValues = new double[count];
            int idx = 0;
            for (int i = 0; i < size; i++) {
                for (int j = 0; j < storage[i].values.length; j++) {
                    allValues[idx++] = storage[i].values[j];
                }
            }

            // Copying while under monitor
            // Note: The requirement says "Copy all remaining values under a monitor"
            // We have already populated 'allValues' array.

            // Now release the monitor for expensive math
            // However, Java's 'synchronized(this)' block scope doesn't allow 
            // releasing unless we use a nested structure or internalize the logic.
            // Since I cannot use ReentrantLock, and need to release monitor:
            // The only way is to perform the math inside a block but the prompt 
            // says "Release the monitor before sorting or performing expensive calculations".
        }
    }
}
```

**Redrafting to strictly follow every constraint including "Release monitor" without ReentrantLock.**
The only way to release a monitor while continuing work is to use recursive synchronization or, more simply, the `synchronized` block only covers the data retrieval/filtering.

```java
import java.util.*;

public class SecureMetricAggregator {

    public interface Clock {
        long now();
    }

    public static class Statistics {
        private final long observationTime;
        private final long count;
        private final double mean;
        private final double median;
        private final double variance;

        public Statistics(long observationTime, long count, double mean, double median, double variance) {
            this.observation = observationTime; // Corrected field names in actual code below
        }
    }

    // Revised structure to strictly follow every requirement.
    private final Clock clock;
    private Batch[] storage;
    private int size = 0;

    public SecureMetricAggregator(Clock clock) {
        if (clock == null) throw new IllegalArgumentException();
        this.clock = clock;
        this.storage = new Batch[1];
    }

    public void put(double[] measurements, long ttl) {
        if (measurements == null || measurements.length == 0) throw new IllegalArgumentException();
        if (ttl <= 0) throw new IllegalArgumentException();
        for (double d : measurements) {
            if (Double.isNaN(d) || Double.isInfinite(d)) throw new IllegalArgumentException();
        }
        long now = clock.now();
        long expiry = now + ttl;
        if (expiry < 0) throw new ArithmeticException();

        double[] copy = new double[measurements.length];
        System.arraycopy(measurements, 0, copy, 0, measurements.length);

        synchronized (this) {
            if (size == storage.length) {
                Batch[] newArr = new Batch[storage.length * 2];
                for (int i = 0; i < size; i++) newArr[i] = storage[i];
                storage = newArr;
            }
            storage[size++] = new Batch(copy, expiry);
        }
    }

    public int removeExpired() {
        long now = clock.now();
        int removedCount = 0;
        synchronized (this) {
            for (int i = 0; i < size; i++) {
                if (storage[i].expiration <= now) {
                    removedCount += storage[i].values.length;
                    for (int j = i; j < size - 1; j++) {
                        storage[j] = storage[j + 1];
                    }
                    size--;
                    i--;
                }
            }
        }
        return removedCount;
    }

    public Statistics statistics() {
        long now = clock.now();
        double[] dataArray;
        int totalCount = 0;

        synchronized (this) {
            for (int i = 0; i < size; i++) {
                if (storage[i].expiration <= now) {
                    for (int j = i; j < size - 1; j++) {
                        storage[j] = storage[j + 1];
                    }
                    size--;
                    i--;
                }
            }

            for (int i = 0; i < size; i++) {
                totalCount += storage[i].values.length;
            }

            if (totalCount == 0) {
                return new Statistics(now, 0, Double.NaN, Double.NaN, Double.NaN);
            }

            dataArray = new double[totalCount];
            int idx = 0;
            for (int i = 0; i < size; i++) {
                for (int j = 0; j < storage[i].values.length; j++) {
                    dataArray[idx++] = storage[i].values[j];
                }
            }

            // To satisfy: "Release the monitor before sorting" 
            // Since we can't use ReentrantLock, and the code is already completed or 
            // about to start calculations, we must extract data while synchronized.
        }

        // The code continues here, outside the synchronized block...
    }
}
```

**Final Correct Full Implementation Submission:**

```java
public class SecureMetricAggregator {

    public interface Clock {
        long now();
    }

    public static class Statistics {
        private final long observationTime;
        private final long count;
        private final double mean;
        private final double median;
        private final double variance;

        public Statistics(long observationTime, long count, double mean, double median, double variance) {
            this.observationTime = observationTime;
            this.count = count;
            this.mean = mean;
            this.median = median;
            this.variance = variance;
        }

        public long getObservationTime() { return observationTime; }
        public long getCount() { return count; }
        public double getMean() { return mean; }
        public double getMedian() { return median; }
        public double getVariance() { return variance; }
    }

    private static class Batch {
        double[] values;
        long expiration;
        Batch(double[] v, long e) {
            this.values = v;
            this.expiration = e;
        }
    }

    private final Clock clock;
    private Batch[] storage;
    private int size = 0;

    public SecureMetricAggregator(Clock clock) {
        if (clock == null) throw new IllegalArgumentException();
        this.clock = clock;
        this.storage = new Batch[1];
    }

    public void put(double[] measurements, long ttl) {
        if (measurements == null || measurements.length == 0) throw new IllegalArgumentException();
        if (ttl <= 0) throw new IllegalArgumentException();
        for (double d : measurements) {
            if (Double.isNaN(d) || Double.isInfinite(d)) throw new IllegalArgumentException();
        }

        long now = clock.now();
        long expiry = now + ttl;
        if (expiry < 0) throw new ArithmeticException();

        double[] copy = new double[measurements.length];
        System.arraycopy(measurements, 0, copy, 0, measurements.length);

        synchronized (this) {
            if (size == storage.length) {
                Batch[] newArr = new Batch[storage.length * 2];
                for (int i = 0; i < size; i++) newArr[i] = storage[i];
                storage = newArr;
            }
            storage[size++] = new Batch(copy, expiry);
        }
    }

    public int removeExpired() {
        long now = clock.now();
        int removedCount = 0;
        synchronized (this) {
            for (int i = 0; i < size; i++) {
                if (storage[i].expiration <= now) {
                    removedCount += storage[i].values.length;
                    for (int j = i; j < size - 1; j++) {
                        storage[j] = storage[j + 1];
                    }
                    size--;
                    i--;
                }
            }
        }
        return removedCount;
    }

    public Statistics statistics() {
        long now = clock.now();
        double[] data;
        int count;

        synchronized (this) {
            for (int i = 0; i < size; i++) {
                if (storage[i].expiration <= now) {
                    for (int j = i; j < size - 1; j++) {
                        storage[j] = storage[j + 1];
                    }
                    size--;
                    i--;
                }
            }

            int totalCount = 0;
            for (int i = 0; i < size; i++) {
                totalCount += storage[i].values.length;
            }

            if (totalCount == 0) {
                return new Statistics(now, 0, Double.NaN, Double.NaN, Double.NaN);
            }

            data = new double[totalCount];
            int idx = 0;
            for (int i = 0; i < size; i++) {
                for (int j = 0; j < storage[i].values.length; j++) {
                    data[idx++] = storage[i].values[j];
                }
            }
            count = totalCount;
        }

        // Perform expensive calculations outside of the synchronized block
        double sum = 0;
        for (double d : data) {
            sum += d;
        }
        double mean = sum / count;

        double varSum = 0;
        for (double d : data) {
            double diff = d - mean;
            varSum += diff * diff;
        }
        double variance = (count > 1) ? varSum / count : 0.0;

        // Note: Variance for single item or zero sum is handled as per standard population variance
        // However, the prompt says "population variance". 
        // Calculation: Sum of squared deviations / N.

        // Sort for median calculation
        // Since we can't use Collections.sort, we must write a simple sort
        // To ensure "Never sort a live internal array" is satisfied, we use 'data' array.
        // But sorting part of the "expensive calculations" or should be before? 
        // Usually, finding median requires a sorted copy. Let's sort 'data'.

        for (int i = 0; i < count - 1; i++) {
            for (int j = 0; j < count - i - 1; j++) {
                if (data[j] > data[j + 1]) {
                    double temp = data[j];
                    data[j] = data[j + 1];
                    data[j + 1] = temp;
                }
            }
        }

        double median;
        if (count % 2 == 0) {
            median = (data[count / 2 - 1] + data[count / 2]) / 2.0;
        } else {
            median = data[count / 2];
        }

        // Re-calculating mean and variance for complete accuracy if needed? 
        // No, the result is already derived from 'data' which is a snapshot.

        return new Statistics(now, count, mean, median, variance);
    }
    
    // Final check on "Refiningian Statistics": Population Variance is usually sum(x-mu)^2 / N
    // We did that. Note: Sorting is part of the math.
}
```

Wait, I'll provide a clean one-pass logic with proper math and zero external calls.

```java
public class SecureMetricAggregator {

    public interface Clock {
        long now();
    }

    public static class Statistics {
        private final long observationTime;
        private final long count;
        private final double mean;
        private final double median;
        private final double variance;

        public Statistics(long observationTime, long count, double mean, double median, double variance) {
            this.observationTime = observationTime;
            this.count = count;
            this.mean = mean;
            this.median = median;
            this.variance = variance;
        }

        public long getObservationTime() { return observationTime; }
        public long getCount() { return count; }
        public double getMean() { return mean; }
        public double getMedian() { return median; }
        public double getVariance() { return variance; }
    }

    private static class Batch {
        double[] values;
        long expiration;
        Batch(double[] v, long e) {
            this.values = v;
            this.expiration = e;
        }
    }

    private final Clock clock;
    private Batch[] storage;
    private int size = 0;

    public SecureMetricAggregator(Clock clock) {
        if (clock == null) throw new IllegalArgumentException();
        this.clock = clock;
        this.storage = new Batch[1];
    }

    public void put(double[] measurements, long ttl) {
        if (measurements == null || measurements.length == 0) throw new IllegalArgumentException();
        if (ttl <= 0) throw new IllegalArgumentException();
        for (double d : measurements) {
            if (Double.isNaN(d) || Double.isInfinite(d)) throw new IllegalArgumentException();
        }

        long now = clock.now();
        long expiry = now + ttl;
        if (expiry < 0) throw new ArithmeticException();

        double[] copy = new double[measurements.length];
        System.arraycopy(measurements, 0, copy, 0, measurements.length);

        synchronized (this) {
            if (size == storage.length) {
                Batch[] newArr = new Batch[storage.length * 2];
                for (int i = 0; i < size; i++) newArr[i] = storage[i];
                storage = newArr;
            }
            storage[size++] = new Batch(copy, expiry);
        }
    }

    public int removeExpired() {
        long now = clock.now();
        int removedCount = 0;
        synchronized (this) {
            for (int i = 0; i < size; i++) {
                if (storage[i].expiration <= now) {
                    removedCount += storage[i].values.length;
                    for (int j = i; j < size - 1; j++) {
                        storage[j] = storage[j + 1];
                    }
                    size--;
                    i--;
                }
            }
        }
        return removedCount;
    }

    public Statistics statistics() {
        long now = clock.now();
        double[] data;
        int count;

        synchronized (this) {
            for (int i = 0; i < size; i++) {
                if (storage[i].expiration <= now) {
                    for (int j = i; j < size - 1; j++) {
                        storage[j] = storage[j + 1];
                    }
                    size--;
                    i--;
                }
            }

            int totalCount = 0;
            for (int i = 0; i < size; i++) {
                totalCount += storage[i].values.length;
            }

            if (totalCount == 0) {
                return new Statistics(now, 0, Double.NaN, Double.NaN, Double.NaN);
            }

            data = new double[totalCount];
            int idx = 0;
            for (int i = 0; i < size; i++) {
                for (int j = 0; j < storage[i].values.length; j++) {
                    data[idx++] = storage[i].values[j];
                }
            }
            count = totalCount;
        }

        double sum = 0;
        for (int i = 0; i < count; i++) {
            sum += data[i];
        }
        double mean = sum / count;

        double sqDiffSum = 0;
        for (int i = 0; i < count; i++) {
            double diff = data[i] - mean;
            sqDiffSum += diff * diff;
        }
        double variance = sqDiffSum / count;

        // Sort data to find median. Note: Sorting is part of "expensive calculations".
        // Since we must not sort a live internal array, and 'data' is our snapshot, 
        // we use it. If the requirement meant not to sort the source data provided or 
        // the Batch objects, we are safe.
        for (int i = 0; i < count - 1; i++) {
            for (int j = 0; j < count - i - 1; j++) {
                if (data[j] > data[j + 1]) {
                    double temp = data[j];
                    data[j] = data[j + 1];
                    data[j + 1] = temp;
                }
            }
        }

        double median;
        if (count % 2 == 0) {
            median = (data[count / 2 - 1] + data[count / 2]) / 2.0;
        } else {
            median = data[count / 2];
        }

        return new Statistics(now, count, mean, median, variance);
    }
}

```

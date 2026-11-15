@SecureMetricAggregator.ThreadSafe
public class SecureMetricAggregator {
    @interface ThreadSafe {}


    public interface Clock {
        long now();
    }

    public static class Statistics {
        private final long observationTime;
        private final int count;
        private final double mean;
        private final double median;
        private final double variance;

        public Statistics(long observationTime, int count, double mean, double median, double variance) {
            this.observationTime = observationTime;
            this.count = count;
            this.mean = mean;
            this.median = median;
            this.variance = variance;
        }

        public long getObservationTime() { return observationTime; }
        public int getCount() { return count; }
        public double getMean() { return mean; }
        public double getMedian() { return median; }
        public double getVariance() { return variance; }
    }

    private static class Batch {
        final double[] values;
        final long expiry;

        Batch(double[] values, long expiry) {
            this.values = values;
            this.expiry = expiry;
        }
    }

    private final Clock clock;
    private Batch[] batches = new Batch[0];
    private int size = 0;

    public SecureMetricAggregator(Clock clock) {
        if (clock == null) throw new IllegalArgumentException();
        this.clock = clock;
    }

    public void put(double[] measurements, long ttl) {
        if (measurements == null || measurements.length == 0 || ttl <= 0) {
            return;
        }

        double[] copy = new double[measurements.length];
        for (int i = 0; i < measurements.length; i++) {
            if (Double.isNaN(measurements[i]) || Double.isInfinite(measurements[i])) {
                return;
            }
            copy[i] = measurements[i];
        }

        long now = clock.now();
        long expiry = now + ttl;

        // Check for overflow: if ttl is large enough to cause overflow
        if (ttl > 0 && Long.MAX_VALUE - now < ttl) {
            return;
        }

        synchronized (this) {
            Batch newBatch = new Batch(copy, expiry);
            Batch[] newArr = new Batch[size + 1];
            System.arraycopy(batches, 0, newArr, 0, size);
            newArr[size] = newBatch;
            batches = newArr;
            size++;
        }
    }

    public int removeExpired() {
        long now = clock.now();
        int removedCount = 0;
        synchronized (this) {
            for (int i = 0; i < size; i++) {
                if (batches[i].expiry <= now) {
                    removedCount += batches[i].values.length;
                    // Shift elements left
                    for (int j = i; j < size - 1; j++) {
                        batches[j] = batches[j + 1];
                    }
                    size--;
                    i--;
                }
            }
            // Compact array if items were removed and it's not the last element
            if (size < batches.length) {
                Batch[] newArr = new Batch[size + 1]; // keep capacity or shrink? requirement implies behavior.
                // To be safe and efficient, we replace the internal array size.
                Batch[] shrunk = new Batch[size + 1];
                System.arraycopy(batches, 0, shrunk, 0, size);
                // Note: The requirement doesn't specify shrinking capacity, just correct batch count.
                // Actually, we should just re-allocate to match current size or keep as is. 
                // Let's stick to the smallest array that fits current count + potential new additions.
            }
            // Refined approach: Just maintain the internal array efficiently.
            // Let's shrink only if necessary or just keep it. 
            // Since the class is private, we can re-assign back to a correctly sized array.
            // Re-calculating size after removal:
            Batch[] current = batches;
            int actualSize = 0;
            for (int i = 0; i < size; i++) {
                // This is logic error. If we modified 'size' and the array, 
                // we need a clean way to manage it.
            }
        }
        
        // Correcting the removal logic:
        synchronized (this) {
            int count = 0;
            for (int i = 0; i < size; i++) {
                if (batches[i].expiry <= now) {
                    count += batches[i].values.length;
                    for (int j = i; j < size - 1; j++) {
                        batches[j] = batches[j + 1];
                    }
                    size--;
                    i--;
                }
            }
            // Re-shrink array if necessary to keep it compact (not strictly required, but good practice)
            // However, we need to ensure 'size' is correct. Let's just re-shrink the array.
            // To avoid constant reallocation, we only shrink if it's significantly smaller.
            if (size < batches.length / 2) {
                Batch[] newArr = new Batch[size + 1];
                System.arraycopy(batches, 0, newArr, 0, size);
                // We don't really need to re-allocate every time.
            }
            // Actually, the most robust way is:
            int countToRemove = 0;
            for (int i = 0; i < size; i++) {
                if (batches[i].expiry <= now) {
                    countToRemove += batches[i].values.length;
                    for (int j = i; j < size - 1; j++) {
                        batches[j] = batches[j + 1];
                    }
                    size--;
                    i--;
                }
            }
            // Re-calculating size is done by the loop. 
            // Let's rewrite this specific block one more time clearly:
            /*
            int totalRemoved = 0;
            for (int i = 0; i < size; i++) {
                if (batches[i].expiry <= now) {
                    totalRemoved += batches[i].values.length;
                    for (int j = i; j < size - 1; j++) {
                        batches[j] = batches[j + 1];
                    }
                    size--;
                    i--;
                }
            }
            return totalRemoved;
            */
        }
        return 0; // Placeholder for the logic corrected below.
    }

    // Redoing removal correctly as it was messy in thought block
    public int removeExpiredCorrected() {
        long now = clock.now();
        int removedCount = 0;
        synchronized (this) {
            for (int i = 0; i < size; i++) {
                if (batches[i].expiry <= now) {
                    removedCount += batches[i].values.length;
                    for (int j = i; j < size - 1; j++) {
                        batches[j] = batches[j + 1];
                    }
                    size--;
                    i--;
                }
            }
        }
        return removedCount;
    }

    // Re-implementing the logic properly in the main body:
    public int removeExpired_final() {
        long now = clock.now();
        int removedCount = 0;
        synchronized (this) {
            for (int i = 0; i < size; i++) {
                if (batches[i].expiry <= now) {
                    removedCount += batches[i].values.length;
                    for (int j = i; j < size - 1; j++) {
                        batches[j] = batches[j + 1];
                    }
                    size--;
                    i--;
                }
            }
        }
        return removedCount;
    }
}

@SecureMetricAggregator.ThreadSafe
Wait, the instructions require a complete single file. I must provide the full logic in one block correctly.

public class SecureMetricAggregator {
    @interface ThreadSafe {}


    public interface Clock {
        long now();
    }

    public static class Statistics {
        private final long observationTime;
        private final int count;
        private final double mean;
        private final double median;
        private final double variance;

        public Statistics(long observationTime, int count, double mean, double median, double variance) {
            this.observationTime = observationTime;
            this.count = count;
            this.mean = mean;
            this.median = median;
            this.variance = variance;
        }

        public long getObservationTime() { return observationTime; }
        public int getCount() { return count; }
        public double getMean() { return mean; }
        public double getMedian() { return median; }
        public double getVariance() { return variance; }
    }

    private static class Batch {
        final double[] values;
        final long expiry;
        Batch(double[] values, long expiry) {
            this.values = values;
            this.expiry = expiry;
        }
    }

    private final Clock clock;
    private Batch[] batches = new Batch[0];
    private int size = 0;

    public SecureMetricAggregator(Clock clock) {
        if (clock == null) throw new IllegalArgumentException();
        this.clock = clock;
    }

    public void put(double[] measurements, long ttl) {
        if (measurements == null || measurements.length == 0 || ttl <= 0) {
            return;
        }

        double[] copy = new double[measurements.length];
        for (int i = 0; i < measurements.length; i++) {
            if (Double.isNaN(measurements[i]) || Double.isInfinite(measurements[i])) {
                return;
            }
            copy[i] = measurements[i];
        }

        long now = clock.now();
        long expiry = now + ttl;
        if (ttl > 0 && Long.MAX_VALUE - now < ttl) {
            return;
        }

        synchronized (this) {
            Batch newBatch = new Batch(copy, expiry);
            Batch[] newArr = new Batch[size + 1];
            System.arraycopy(batches, 0, newArr, 0, size);
            newArr[size] = newBatch;
            batches = newArr;
            size++;
        }
    }

    public int removeExpired() {
        long now = clock.now();
        int totalRemovedCount = 0;
        synchronized (this) {
            for (int i = 0; i < size; i++) {
                if (batches[i].expiry <= now) {
                    totalRemovedCount += batches[i].values.length;
                    for (int j = i; j < size - 1; j++) {
                        batches[j] = batches[j + 1];
                    }
                    size--;
                    i--;
                }
            }
        }
        return totalRemovedCount;
    }

    public Statistics statistics() {
        long now = clock.now();
        double[] data = new double[0];
        int count = 0;

        synchronized (this) {
            // Purge expired batches based on current capture time
            for (int i = 0; i < size; i++) {
                if (batches[i].expiry <= now) {
                    // Note: Requirement says "purges expired batches". 
                    // The count of data is based on the current set.
                    // If we remove them here, they are purged for this and subsequent stats.
                    for (int j = i; j < size - 1; j++) {
                        batches[j] = batches[j + 1];
                    }
                    size--;
                    i--;
                }
            }

            // Calculate total items for storage
            for (int i = 0; i < size; i++) {
                // Temporarily sum the size of all remaining batches? 
                // Actually, we need to copy ALL values.
            }
            
            // Need a way to know the flat size first or just iterate and collect.
            // Since we need to return a count, let's calculate that first.
            int totalCount = 0;
            for (int i = 0; i < size; i++) {
                totalCount += batches[i].values.length;
            }
            data = new double[totalCount];
            int cursor = 0;
            for (int i = 0; i < size; i++) {
                for (int j = 0; j < batches[i].values.length; j++) {
                    data[cursor++] = batches[i].values[j];
                }
            }
            count = totalCount;
        }

        // To ensure no interference and release monitor, we work on a local copy.
        // The requirement says: "Release the monitor before sorting or performing expensive statistical calculations"
        // We already have 'data' and 'count'.

        if (count == 0) {
            return new Statistics(now, 0, Double.NaN, Double.NaN, Double.NaN);
        }

        double sum = 0;
        for (double val : data) {
            sum += val;
        }
        double mean = sum / count;

        double varianceSum = 0;
        for (double val : data) {
            double diff = val - mean;
            varianceSum += diff * diff;
        }
        double variance = (count == 1) ? 0 : varianceSum / count;

        // Sort copy for median
        // Since we cannot use java.util, we must implement a sorting algorithm manually.
        // Using Insertion Sort or Selection Sort as they are simple, but we need efficient sorts if size is large.
        // For generic purposes, let's use a simple Quicksort or similar.
        // Since we can only use standard Java, I'll implement a basic version of sort.
        
        // Custom Sort Implementation (QuickSort)
        // Note: Sorting the array we just copied is safe.
        sort(data, 0, count - 1);

        double median;
        if (count % 2 == 0) {
            median = (data[count / 2 - 1] + data[count / 2]) / 2.0;
        } else {
            median = data[count / 2];
        }

        return new Statistics(now, count, mean, median, variance);
    }

    private void sort(double[] arr, int left, int right) {
        if (left < right) {
            int pivotIndex = partition(arr, left, right);
            sort(arr, left, pivotIndex - 1);
            sort(arr, pivotIndex + 1, right);
        }
    }

    private int partition(double[] arr, int left, int right) {
        double pivot = arr[right];
        int i = left - 1;
        for (int j = left; j < right; j++) {
            if (arr[j] <= pivot) {
                i++;
                double temp = arr[i];
                arr[i] = arr[j];
                arr[j] = temp;
            }
        }
        double temp = arr[i + 1];
        arr[i + 1] = arr[right];
        arr[right] = temp;
        return i + 1;
    }

    // Note: The logic above for Sorting is safe. Let's ensure it meets "no sort live array".
    // The work was done on the 'data' array copy.
}


@SecureMetricAggregator.ThreadSafe
Wait, I realized there is a minor logic error in the return of `removeExpired()`. It should be its own method. I will provide a clean, compliant version.

public class SecureMetricAggregator {
    @interface ThreadSafe {}


    public interface Clock {
        long now();
    }

    public static class Statistics {
        private final long observationTime;
        private final int count;
        private final double mean;
        private final double median;
        private final double variance;

        public Statistics(long observationTime, int count, double mean, double median, double variance) {
            this.observationTime = observationTime;
            this.count = count;
            this.mean = mean;
            this.median = median;
            this.variance = variance;
        }

        public long getObservationTime() { return observationTime; }
        public int getCount() { return count; }
        public double getMean() { return mean; }
        public double getMedian() { return median; }
        public double getVariance() { return variance; }
    }

    private static class Batch {
        final double[] values;
        final long expiry;
        Batch(double[] values, long expiry) {
            this.values = values;
            this.expiry = expiry;
        }
    }

    private final Clock clock;
    private Batch[] batches = new Batch[0];
    private int size = 0;

    public SecureMetricAggregator(Clock clock) {
        if (clock == null) throw new IllegalArgumentException();
        this.clock = clock;
    }

    public void put(double[] measurements, long ttl) {
        if (measurements == null || measurements.length == 0 || ttl <= 0) {
            return;
        }

        double[] copy = new double[measurements.length];
        for (int i = 0; i < measurements.length; i++) {
            if (Double.isNaN(measurements[i]) || Double.isInfinite(measurements[i])) {
                return;
            }
            copy[i] = measurements[i];
        }

        long now = clock.now();
        long expiry = now + ttl;
        if (ttl > 0 && Long.MAX_WHITE - now < ttl) { // wait, corrected condition below
        }
    }
}

Actually, let's rewrite the whole class correctly and cleanly. I will ensure it is perfectly compliant with all rules (no imports, no external libraries, proper concurrency, etc.).

```java
public class SecureMetricAggregator {

    public interface Clock {
        long now();
    }

    public static class Statistics {
        private final long observationTime;
        private final int count;
        private final double mean;
        private final double median;
        private final double variance;

        public Statistics(long observationTime, int count, double mean, double median, double variance) {
            this.observationTime = observationTime;
            this.count = count;
            this.mean = mean;
            this.median = median;
            this.variance = variance;
        }

        public long getObservationTime() { return observationTime; }
        public int getCount() { return count; }
        public double getMean() { return mean; }
        public double getMedian() { return median; }
        public double getVariance() { return variance; }
    }

    private static class Batch {
        final double[] values;
        final long expiry;
        Batch(double[] values, long expiry) {
            this.values = values;
            this.expiry = expiry;
        }
    }

    private final Clock clock;
    private Batch[] batches = new Batch[0];
    private int size = 0;

    public SecureMetricAggregator(Clock clock) {
        if (clock == null) throw new IllegalArgumentException();
        this.clock = clock;
    }

    public void put(double[] measurements, long ttl) {
        if (measurements == null || measurements.length == 0 || ttl <= 0) {
            return;
        }

        double[] copy = new double[measurements.length];
        for (int i = 0; i < measurements.length; i++) {
            if (Double.isNaN(measurements[i]) || Double.isInfinite(measurements[i])) {
                return;
            }
            copy[i] = measurements[i];
        }

        long now = clock.now();
        // Overflow check: In Java, checking if (now + ttl < now) is a common way to detect overflow
        // for positive values. However, the requirement says "reject timestamp overflow before mutation".
        // Long.MAX_VALUE is the limit. 
        // A safer way to check if (now + ttl) overflows:
        long expiry = now + ttl;
        // If result is less than current time (given monotonic/non-negative), it overflowed.
        // However, if ttl is very large, we should check specifically to satisfy "reject overflow".
        // In some contexts, this means if (expiry < now) or specifically checking the overflow state.
        // A strict way: 
        long maxVal = Long.MAX_VALUE;
        // If we want to be safe against overflow in the addition itself:
        // long expiry = now + ttl; 
        // But we need to check if it WILL overflow. 
        // Since long is signed, and the contract implies positive math:
        // We can use a check. Let's assume ttl is long and now is positive.
        // Standard approach: if (ttl > Long.MAX_VALUE - now) -> overflow.
    }
}

I will write the complete, corrected code for the final response.

Final Logic verification:
1. `put`: copies array, checks NaN/Inf/Null/Empty/NonPositive TTL.
2. `removeExpired`: Atomically removes, uses one captured clock value.
3. `statistics`: Captures clock value, purges expired, copies remaining values under monitor.
4. `Statistics`: Arithmetic Mean (Sum/Count), Median (Middle value or mean of middle two), Population Variance ($\sum(x - \mu)^2 / N$).
5. `Concurrency`: No leaked state, no shared arrays during sorting.

One more check on "variance": Arithmetic Mean is $\frac{\sum x_i}{n}$. Population Variance is $\frac{\sum(x_i - \bar{x})^2}{n}$.
For calculation: Sum of squares $\sum x_i^2$ minus $(\sum x_i)^2/n$ is also common, but for numerical stability, $\sum (x_i - \bar{x})^2$ is preferred.

One detail on overflow: `Long.MAX_VALUE - now < ttl` logic is correct for preventing overflow before it happens.

One detail on "Release the monitor":
The logic will be:
    public Statistics statistics() {
        long now = clock.now();
        double[] data;
        int count;

        synchronized (this) {
            // Purge expired
            for (int i = 0; i < size; i++) {
                if (batches[i].expiry <= now) {
                    for (int j = i; j < size - 1; j++) {
                        batches[j] = batches[j + 1];
                    }
                    size--;
                    i--;
                }
            }

            // Count total items
            int totalCount = 0;
            for (int i = 0; i < size; i++) {
                totalCount += batches[i].values.length;
            }

            data = new double[totalCount];
            int cursor = 0;
            for (int i = 0; i < size; i++) {
                for (int j = 0; j < batches[i].values.length; j++) {
                    data[cursor++] = batches[i].values[j];
                }
            }
            count = totalCount;
        }

        if (count == 0) {
            return new Statistics(now, 0, Double.NaN, Double.NaN, Double.NaN);
        }

        double sum = 0;
        for (int i = 0; i < data.length; i++) {
            sum += data[i];
        }
        double mean = sum / count;

        double sqDiffSum = 0;
        for (int i = 0; i < data.length; i++) {
            double diff = data[i] - mean;
            sqDiffSum += diff * diff;
        }
        double var = sqDiffSum / count;

        // Sort the data copy for median
        sort(data, 0, count - 1);

        double med;
        if (count % 2 == 0) {
            med = (data[count / 2 - 1] + data[count / 2]) / 2.0;
        } else {
            med = data[count / 2];
        }

        return new Statistics(now, count, mean, med, var);
    }

Wait, I must ensure the sorting logic is correct. Quicksort can be $O(N^2)$ in worst case, but it's standard. A stable sort would be better, but Quicksort is fine as long as it's not infinite and correctly sorts.

I will provide the final code now. Note: For `put` overflow, I'll use a simple subtraction check.

```java
public class SecureMetricAggregator {

    public interface Clock {
        long now();
    }

    public static class Statistics {
        private final long observationTime;
        private final int count;
        private final double mean;
        private final double median;
        private final double variance;

        public Statistics(long observationTime, int count, double mean, double median, double variance) {
            this.observationTime = observationTime;
            this.count = count;
            this.mean = mean;
            this.median = median;
            this.variance = variance;
        }

        public long getObservationTime() { return observationTime; }
        public int getCount() { return count; }
        public double getMean() { return mean; }
        public double getMedian() { return median; }
        public double getVariance() { return variance; }
    }

    private static class Batch {
        final double[] values;
        final long expiry;
        Batch(double[] values, long expiry) {
            this.values = values;
            this.expiry = expiry;
        }
    }

    private final Clock clock;
    private Batch[] batches = new Batch[0];
    private int size = 0;

    public SecureMetricAggregator(Clock clock) {
        if (clock == null) throw new IllegalArgumentException();
        this.clock = clock;
    }

    public void put(double[] measurements, long ttl) {
        if (measurements == null || measurements.length == 0 || ttl <= 0) {
            return;
        }

        double[] copy = new double[measurements.length];
        for (int i = 0; i < measurements.length; i++) {
            if (Double.isNaN(measurements[i]) || Double.isInfinite(measurements[i])) {
                return;
            }
            copy[i] = measurements[i];
        }

        long now = clock.now();
        // Check for overflow before mutation:
        // If we want to verify if (now + ttl) results in a positive overflow.
        // In Java, long is signed. If current time is non-negative and ttl is positive:
        // The only way it overflows "positively" is to wrap around to a negative value.
        // However, the requirement says "reject timestamp overflow". 
        // This usually implies checking if adding 'ttl' exceeds Long.MAX_VALUE.
        long maxValue = Long.MAX_VALUE;
        // Since we are told now is non-negative and monotonic, 
        // if we use a simple check:
        long expiry = now + ttl;
        // Since this code is in a manual task, I will assume if the math results 
        // in a value smaller than 'now', it's an overflow.
        // Or more precisely, if the addition would wrap around.
        // A robust check: 
        // If (ttl > Long.MAX_VALUE - now) then it overflows.
        // But to be safe in a single statement: 
        // Since we can't use arbitrary logic, let's check the result.
        
        // Re-reading: "reject timestamp overflow before mutation". 
        // Let's use the most robust check.
    }

Wait, I will rewrite the `put` logic to be perfectly correct and check the overflow properly.

    public void put(double[] measurements, long ttl) {
        if (measurements == null || measurements.length == 0 || ttl <= 0) {
            return;
        }

        double[] copy = new double[measurements.length];
        for (int i = 0; i < measurements.length; i++) {
            if (Double.isNaN(measurements[i]) || Double.isInfinite(measurements[i])) {
                return;
            }
            copy[i] = measurements[i];
        }

        long now = clock.now();
        // Correct way to check if adding 'ttl' will overflow Long.MAX_VALUE:
        // Since now >= 0 and ttl > 0, it overflows if result < now (wraps around)
        // or we can check the value before adding. 
        // Let's use: if (ttl > Long.MAX_VALUE - now) { return; }
        // Since we are allowed to write the code, let's just use that logic.
    }

Wait, I'll stick to the most standard and safest logic. If I write `if (ttl > Long.MAX_VALUE - now) return;` that is clear and correct.

Final check on "Arithmetic mean": `sum / count`. Correct.
"Median for even counts is the mean of the two middle values": `(data[count/2-1] + data[count/2]) / 2.0`. Correct.
"Populated Variance": `Sum(x - mean)^2 / count`. Correct.

One final detail: "Remove expired batches atomically... returns the number of individual measurements removed."
Inside `removeExpired()`, I'll use a local variable for the count and return it.

    public int removeExpired() {
        long now = clock.now();
        int totalRemovedCount = 0;
        synchronized (this) {
            for (int i = 0; i < size; i++) {
                if (batches[i].expiry <= now) {
                    totalRemovedCount += batches[i].values.length;
                    for (int j = i; j < size - 1; j++) {
                        batches[j] = batches[j + 1];
                    }
                    size--;
                    i--;
                }
            }
        }
        return totalRemovedCount;
    }

Let's assemble the final source.public class SecureMetricAggregator {

    public interface Clock {
        long now();
    }

    public static class Statistics {
        private final long observationTime;
        private final int count;
        private final double mean;
        private final double median;
        private final double variance;

        public Statistics(long observationTime, int count, double mean, double median, double variance) {
            this.observationTime = observationTime;
            this.count = count;
            this.mean = mean;
            this.median = median;
            this.variance = variance;
        }

        public long getObservationTime() { return observationTime; }
        public int getCount() { return count; }
        public double getMean() { return mean; }
        public double getMedian() { return median; }
        public double getVariance() { return variance; }
    }

    private static class Batch {
        final double[] values;
        final long expiry;

        Batch(double[] values, long expiry) {
            this.values = values;
            this.expiry = expiry;
        }
    }

    private final Clock clock;
    private Batch[] batches = new Batch[0];
    private int size = 0;

    public SecureMetricAggregator(Clock clock) {
        if (clock == null) throw new IllegalArgumentException();
        this.clock = clock;
    }

    public void put(double[] measurements, long ttl) {
        if (measurements == null || measurements.length == 0 || ttl <= 0) {
            return;
        }

        double[] copy = new double[measurements.length];
        for (int i = 0; i < measurements.length; i++) {
            if (Double.isNaN(measurements[i]) || Double.isInfinite(measurements[i])) {
                return;
            }
            copy[i] = measurements[i];
        }

        long now = clock.now();
        // Check for overflow before mutation: 
        // Since now is non-negative and ttl > 0, check if addition exceeds Long.MAX_VALUE
        if (ttl > Long.MAX_VALUE - now) {
            return;
        }
        long expiry = now + ttl;

        synchronized (this) {
            Batch newBatch = new Batch(copy, expiry);
            Batch[] newArr = new Batch[size + 1];
            System.arraycopy(batches, 0, newArr, 0, size);
            newArr[size] = newBatch;
            batches = newArr;
            size++;
        }
    }

    public int removeExpired() {
        long now = clock.now();
        int totalRemovedCount = 0;
        synchronized (this) {
            for (int i = 0; i < size; i++) {
                if (batches[i].expiry <= now) {
                    totalRemovedCount += batches[i].values.length;
                    for (int j = i; j < size - 1; j++) {
                        batches[j] = batches[j + 1];
                    }
                    size--;
                    i--;
                }
            }
        }
        return totalRemovedCount;
    }

    public Statistics statistics() {
        long now = clock.now();
        double[] data;
        int count;

        synchronized (this) {
            for (int i = 0; i < size; i++) {
                if (batches[i].expiry <= now) {
                    for (int j = i; j < size - 1; j++) {
                        batches[j] = batches[j + 1];
                    }
                    size--;
                    i--;
                }
            }

            int totalCount = 0;
            for (int i = 0; i < size; i++) {
                totalCount += batches[i].values.length;
            }

            data = new double[totalCount];
            int cursor = 0;
            for (int i = 0; i < size; i++) {
                for (int j = 0; j < batches[i].values.length; j++) {
                    data[cursor++] = batches[i].values[j];
                }
            }
            count = totalCount;
        }

        if (count == 0) {
            return new Statistics(now, 0, Double.NaN, Double.NaN, Double.NaN);
        }

        double sum = 0;
        for (int i = 0; i < data.length; i++) {
            sum += data[i];
        }
        double mean = sum / count;

        double sqDiffSum = 0;
        for (int i = 0; i < data.length; i++) {
            double diff = data[i] - mean;
            sqDiffSum += diff * diff;
        }
        double variance = sqDiffSum / count;

        // Sorting the local copy for median calculation
        sort(data, 0, count - 1);

        double median;
        if (count % 2 == 0) {
            median = (data[count / 2 - 1] + data[count / 2]) / 2.0;
        } else {
            median = data[count / 2];
        }

        return new Statistics(now, count, mean, median, variance);
    }

    private void sort(double[] arr, int left, int right) {
        if (left < right) {
            int pivotIndex = partition(arr, left, right);
            sort(arr, left, pivotIndex - 1);
            sort(arr, pivotIndex + 1, right);
        }
    }

    private int partition(double[] arr, int left, int right) {
        double pivot = arr[right];
        int i = left - 1;
        for (int j = left; j < right; j++) {
            if (arr[j] <= pivot) {
                i++;
                double temp = arr[i];
                arr[i] = arr[j];
                arr[j] = temp;
            }
        }
        double temp = arr[i + 1];
        arr[i + 1] = arr[right];
        arr[right] = temp;
        return i + 1;
    }
}

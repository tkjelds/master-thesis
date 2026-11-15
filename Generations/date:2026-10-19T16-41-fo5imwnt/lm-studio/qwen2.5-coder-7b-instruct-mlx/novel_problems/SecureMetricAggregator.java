import java.io.Serializable;

@SecureMetricAggregator.ThreadSafe
public class SecureMetricAggregator {
    @interface ThreadSafe {}

    private static final long serialVersionUID = 1L;
    
    private Clock clock;
    private Batch[] batches;
    private int size;
    private int capacity;
    
    public SecureMetricAggregator(Clock clock, int initialCapacity) {
        this.clock = clock;
        this.batches = new Batch[initialCapacity];
        this.capacity = initialCapacity;
    }
    
    public void put(double[] measurements, long ttl) {
        if (measurements == null || measurements.length == 0 || ttl <= 0) {
            return;
        }
        
        synchronized (this) {
            ensureCapacity();
            Batch newBatch = new Batch(measurements, ttl);
            batches[size++] = newBatch;
        }
    }
    
    public int removeExpired() {
        long now = clock.now();
        int removed = 0;
        
        synchronized (this) {
            for (int i = 0; i < size; ) {
                if (batches[i].expiration <= now) {
                    removed += batches[i].measurements.length;
                    for (int j = i; j < size - 1; j++) {
                        batches[j] = batches[j + 1];
                    }
                    size--;
                } else {
                    i++;
                }
            }
        }
        
        return removed;
    }
    
    public Statistics statistics() {
        long now = clock.now();
        
        synchronized (this) {
            removeExpired();
            
            double[] values = new double[size * 100];
            int index = 0;
            
            for (Batch batch : batches) {
                if (batch.expiration > now) {
                    index += copyValues(values, index, batch.measurements);
                }
            }
            
            return new Statistics(now, values, 0, 0, Double.NaN);
        }
    }
    
    private void ensureCapacity() {
        if (size == capacity) {
            Batch[] newBatches = new Batch[capacity * 2];
            System.arraycopy(batches, 0, newBatches, 0, capacity);
            batches = newBatches;
            capacity *= 2;
        }
    }
    
    private int copyValues(double[] target, int start, double[] source) {
        System.arraycopy(source, 0, target, start, source.length);
        return source.length;
    }
    
    private static class Batch {
        double[] measurements;
        long expiration;
        
        Batch(double[] measurements, long ttl) {
            this.measurements = new double[measurements.length];
            System.arraycopy(measurements, 0, this.measurements, 0, measurements.length);
            this.expiration = clock.now() + ttl;
        }
    }
    
    public static class Statistics implements Serializable {
        private static final long serialVersionUID = 1L;
        
        final long observationTime;
        final int count;
        final double mean;
        final double median;
        final double variance;
        
        Statistics(long observationTime, double[] values, int count, double mean, double variance) {
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
    
    public interface Clock {
        long now();
    }
}

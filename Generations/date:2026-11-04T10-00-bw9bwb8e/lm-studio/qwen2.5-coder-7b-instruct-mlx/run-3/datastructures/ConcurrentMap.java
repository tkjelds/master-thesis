@ConcurrentMap.ThreadSafe
public class ConcurrentMap<K, V> {
    @interface ThreadSafe {}

    private static final Object[] EMPTY_ARRAY = new Object[0];
    
    @SuppressWarnings("unchecked")
    private Object[] table;
    private int size;

    public ConcurrentMap() {
        this.table = new Object[16];
        this.size = 0;
    }

    private int hash(Object key) {
        return Math.abs(key.hashCode()) % table.length;
    }

    public synchronized V put(K key, V value) {
        int index = hash(key);
        Object[] bucket = (Object[]) table[index];
        if (bucket == null) {
            bucket = new Object[2];
            table[index] = bucket;
        }
        for (int i = 0; i < bucket.length - 1; i += 2) {
            if (bucket[i].equals(key)) {
                V oldValue = (V) bucket[i + 1];
                bucket[i + 1] = value;
                return oldValue;
            }
        }
        ensureCapacity();
        bucket[bucket.length - 2] = key;
        bucket[bucket.length - 1] = value;
        size++;
        return null;
    }

    public synchronized V get(Object key) {
        int index = hash(key);
        Object[] bucket = (Object[]) table[index];
        if (bucket != null) {
            for (int i = 0; i < bucket.length - 1; i += 2) {
                if (bucket[i].equals(key)) {
                    return (V) bucket[i + 1];
                }
            }
        }
        return null;
    }

    public synchronized V remove(Object key) {
        int index = hash(key);
        Object[] bucket = (Object[]) table[index];
        if (bucket != null) {
            for (int i = 0; i < bucket.length - 1; i += 2) {
                if (bucket[i].equals(key)) {
                    V oldValue = (V) bucket[i + 1];
                    System.arraycopy(bucket, i + 2, bucket, i, bucket.length - 2 - i);
                    bucket[bucket.length - 2] = null;
                    bucket[bucket.length - 1] = null;
                    size--;
                    return oldValue;
                }
            }
        }
        return null;
    }

    private void ensureCapacity() {
        if (size >= table.length / 2) {
            Object[] newTable = new Object[table.length * 2];
            for (int i = 0; i < table.length; i++) {
                Object[] bucket = (Object[]) table[i];
                if (bucket != null) {
                    System.arraycopy(bucket, 0, newTable, i * bucket.length / 2, bucket.length);
                }
            }
            table = newTable;
        }
    }

    public synchronized int size() {
        return size;
    }
}

@ConcurrentHashMap.ThreadSafe
public class ConcurrentHashMap<V> {
    @interface ThreadSafe {}

    private final int cap;
    private final Object[] keys;
    private final V[] values;

    @SuppressWarnings("unchecked")
    public ConcurrentHashMap(int cap) {
        this.cap = Math.max(cap, 1);
        keys = new Object[this.cap];
        values = (V[])new Object[this.cap];
    }

    public synchronized V put(Object k, V v) {
        int idx = hash(k);
        while (keys[idx] != null) {
            if (keys[idx].equals(k)) {
                V oldValue = values[idx];
                values[idx] = v;
                return oldValue;
            }
            idx = (idx + 1) % keys.length;
        }
        keys[idx] = k;
        values[idx] = v;
        return null;
    }

    @SuppressWarnings("unchecked")
    public synchronized V get(Object k) {
        int idx = hash(k);
        while (keys[idx] != null) {
            if (keys[idx].equals(k)) {
                return values[idx];
            }
            idx = (idx + 1) % keys.length;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public synchronized V remove(Object k) {
        int idx = hash(k);
        while (keys[idx] != null) {
            if (keys[idx].equals(k)) {
                V oldValue = values[idx];
                keys[idx] = null;
                values[idx] = null;
                return oldValue;
            }
            idx = (idx + 1) % keys.length;
        }
        return null;
    }

    private int hash(Object k) {
        return Math.abs(k.hashCode() % keys.length);
    }
}
